/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.export

import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.backend.js.JsIrBackendContext
import org.jetbrains.kotlin.ir.backend.js.transformers.irToJs.JsAstUtils
import org.jetbrains.kotlin.ir.backend.js.transformers.irToJs.defineProperty
import org.jetbrains.kotlin.ir.backend.js.transformers.irToJs.jsAssignment
import org.jetbrains.kotlin.ir.backend.js.transformers.irToJs.prototypeOf
import org.jetbrains.kotlin.ir.backend.js.transformers.irToJs.jsElementAccess
import org.jetbrains.kotlin.ir.backend.js.utils.*
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.util.companionObject
import org.jetbrains.kotlin.ir.util.isEffectivelyExternal
import org.jetbrains.kotlin.ir.util.isEnumEntry
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.js.backend.ast.*
import org.jetbrains.kotlin.util.collectionUtils.filterIsInstanceAnd

class ExportModelToJsStatements(
    private val namer: IrNamer,
    private val backendContext: JsIrBackendContext,
    private val declareNewNamespace: (String) -> String
) {
    private val namespaceToRefMap = mutableMapOf<String, JsNameRef>()

    fun generateModuleExport(module: ExportedModule, internalModuleName: JsName): List<JsStatement> {
        return module.declarations.flatMap { generateDeclarationExport(it, JsNameRef(internalModuleName)) }
    }

    fun generateDeclarationExport(declaration: ExportedDeclaration, namespace: JsExpression?): List<JsStatement> {
        return when (declaration) {
            is ExportedNamespace -> {
                require(namespace != null) { "Only namespaced namespaces are allowed" }
                val statements = mutableListOf<JsStatement>()
                val elements = declaration.name.split(".")
                var currentNamespace = ""
                var currentRef: JsExpression = namespace
                for (element in elements) {
                    val newNamespace = "$currentNamespace$$element"
                    val newNameSpaceRef = namespaceToRefMap.getOrPut(newNamespace) {
                        val varName = JsName(declareNewNamespace(newNamespace), false)
                        val namespaceRef = jsElementAccess(element, currentRef)
                        statements += JsVars(
                            JsVars.JsVar(
                                varName,
                                JsAstUtils.or(
                                    namespaceRef,
                                    jsAssignment(
                                        namespaceRef,
                                        JsObjectLiteral()
                                    )
                                )
                            )
                        )
                        JsNameRef(varName)
                    }
                    currentRef = newNameSpaceRef
                    currentNamespace = newNamespace
                }
                statements + declaration.declarations.flatMap { generateDeclarationExport(it, currentRef) }
            }

            is ExportedFunction -> {
                val name = namer.getNameForStaticDeclaration(declaration.ir)
                if (namespace == null) {
                    listOf(JsExport(name, alias = JsName(declaration.name, false)))
                } else {
                    listOf(
                        jsAssignment(
                            jsElementAccess(declaration.name, namespace),
                            JsNameRef(name)
                        ).makeStmt()
                    )
                }
            }

            is ExportedConstructor -> emptyList()
            is ExportedConstructSignature -> emptyList()

            is ExportedProperty -> {
                require(namespace != null) { "Only namespaced properties are allowed" }
                val getter = declaration.irGetter?.let { JsNameRef(namer.getNameForStaticDeclaration(it)) }
                val setter = declaration.irSetter?.let { JsNameRef(namer.getNameForStaticDeclaration(it)) }
                listOf(defineProperty(namespace, declaration.name, getter, setter).makeStmt())
            }

            is ErrorDeclaration -> emptyList()

            is ExportedObject -> {
                require(namespace != null) { "Only namespaced properties are allowed" }

                val newNameSpace = jsElementAccess(declaration.name, namespace)
                val getter = namer.getNameForStaticDeclaration(declaration.irGetter).makeRef()

                val exportedMembers = declaration.generateMembersDeclarations()
                val staticsExport = declaration.generateStaticDeclarations(newNameSpace)

                listOf(defineProperty(namespace, declaration.name, getter, null).makeStmt()) + exportedMembers + staticsExport
                    .wrapWithExportComment("'${declaration.name}' object")
            }

            is ExportedClass -> {
                if (declaration.isInterface) return emptyList()
                val newNameSpace = jsElementAccess(declaration.name, namespace)
                val name = namer.getNameForStaticDeclaration(declaration.ir)
                val klassExport =
                    if (namespace == null) {
                        JsExport(name, alias = JsName(declaration.name, false))
                    } else {
                        jsAssignment(newNameSpace, JsNameRef(name)).makeStmt()
                    }

                val exportedMembers = declaration.generateMembersDeclarations()
                val staticsExport = declaration.generateStaticDeclarations(newNameSpace)

                val innerClassesAssignments = declaration.nestedClasses
                    .filter { it.ir.isInner }
                    .map { it.generateInnerClassAssignment(declaration) }


                (listOf(klassExport) + exportedMembers + staticsExport + innerClassesAssignments)
                    .wrapWithExportComment("'$name' class")
            }
        }
    }

    private fun ExportedClassLike.generateStaticDeclarations(newNameSpace: JsExpression): List<JsStatement> {
        // These are only used when exporting secondary constructors annotated with @JsName
        val staticFunctions = members
            .filter { it is ExportedFunction && it.isStatic }
            .takeIf { !ir.isInner }.orEmpty()

        // Nested objects are exported as static properties
        val staticProperties = members.mapNotNull {
            (it as? ExportedObject)?.takeIf { !it.ir.isInner }
                ?: (it as? ExportedProperty)?.takeIf { it.isStatic }
        }

        return (staticFunctions + staticProperties + nestedClasses)
            .flatMap { generateDeclarationExport(it, newNameSpace) }
    }

    private fun ExportedClassLike.generateMembersDeclarations(): List<JsStatement> {
        return members
            .mapNotNull { member ->
                when (member) {
                    is ExportedProperty -> member.generatePrototypeAssignmentIn(this)
                    is ExportedFunction -> member.takeIf { !it.isStatic }
                        ?.let { it.generatePrototypeAssignmentIn(this) }
                    else -> null
                }
            }
    }

    private fun ExportedFunction.generatePrototypeAssignmentIn(owner: ExportedClassLike): JsStatement {
        val classPrototype = owner.prototypeRef()
        val currentFunctionExportedName = ir.getJsNameOrKotlinName().asString()
        val currentFunctionKotlinName = namer.getNameForMemberFunction(ir.realOverrideTarget)

        return jsAssignment(
            JsNameRef(currentFunctionExportedName, classPrototype),
            JsNameRef(currentFunctionKotlinName, classPrototype),
        ).makeStmt()

    }

    private fun ExportedProperty.generatePrototypeAssignmentIn(owner: ExportedClassLike): JsStatement? {
        if (owner.ir.isInterface || owner.ir.isEnumEntry) {
            return null
        }

        val property = ir ?: return null
        val classPrototypeRef = owner.prototypeRef()

        if (property.getter?.extensionReceiverParameter != null || property.setter?.extensionReceiverParameter != null) {
            return null
        }

        if (property.isFakeOverride && !property.isEnumFakeOverriddenDeclaration(backendContext)) {
            return null
        }

        // Don't generate `defineProperty` if the property overrides a property from an exported class,
        // because we've already generated `defineProperty` for the base class property.
        // In other words, we only want to generate `defineProperty` once for each property.
        // The exception is case when we override val with var,
        // so we need regenerate `defineProperty` with setter.
        val noOverriddenGetter = property.getter?.overriddenSymbols?.isEmpty() == true

        val overriddenExportedGetter = (property.getter?.overriddenSymbols?.isNotEmpty() == true &&
                property.getter?.isOverriddenExported(backendContext) == true)

        val noOverriddenExportedSetter = property.setter?.isOverriddenExported(backendContext) == false

        val needsOverride = (overriddenExportedGetter && noOverriddenExportedSetter) ||
                property.isEnumFakeOverriddenDeclaration(backendContext)

        if (
            !noOverriddenGetter && !needsOverride &&
            property.getter?.overridesExternal() != true &&
            property.getJsName() == null
        ) {
            return null
        }

        // Use "direct dispatch" for final properties, i. e. instead of this:
        //     Object.defineProperty(Foo.prototype, 'prop', {
        //         configurable: true,
        //         get: function() { return this._get_prop__0_k$(); },
        //         set: function(v) { this._set_prop__a4enbm_k$(v); }
        //     });
        // emit this:
        //     Object.defineProperty(Foo.prototype, 'prop', {
        //         configurable: true,
        //         get: Foo.prototype._get_prop__0_k$,
        //         set: Foo.prototype._set_prop__a4enbm_k$
        //     });

        val getterForwarder = if (property.getter?.modality == Modality.FINAL) property.getter?.accessorRef(classPrototypeRef)
        else {
            property.getter?.propertyAccessorForwarder("getter forwarder") { getterRef ->
                JsReturn(
                    JsInvocation(
                        getterRef
                    )
                )
            }
        }

        val setterForwarder = if (property.setter?.modality == Modality.FINAL) property.setter?.accessorRef(classPrototypeRef)
        else {
            property.setter?.let {
                val setterArgName = JsName("value", false)
                it.propertyAccessorForwarder("setter forwarder") { setterRef ->
                    JsInvocation(
                        setterRef,
                        JsNameRef(setterArgName)
                    ).makeStmt()
                }?.apply {
                    parameters.add(JsParameter(setterArgName))
                }
            }
        }

        return JsExpressionStatement(
            defineProperty(
                classPrototypeRef,
                namer.getNameForProperty(property).ident,
                getter = getterForwarder,
                setter = setterForwarder
            )
        )
    }

    private fun IrSimpleFunction.overridesExternal(): Boolean {
        if (this.isEffectivelyExternal()) return true

        return this.overriddenSymbols.any { it.owner.overridesExternal() }
    }


    private fun ExportedClassLike.prototypeRef(): JsNameRef {
        val classRef = namer.getNameForStaticDeclaration(ir).makeRef()
        return prototypeOf(classRef)
    }

    private fun IrSimpleFunction.propertyAccessorForwarder(
        description: String,
        callActualAccessor: (JsNameRef) -> JsStatement
    ): JsFunction? =
        when (visibility) {
            DescriptorVisibilities.PRIVATE -> null
            else -> JsFunction(
                emptyScope,
                JsBlock(callActualAccessor(JsNameRef(namer.getNameForMemberFunction(this), JsThisRef()))),
                description
            )
        }

    private fun IrSimpleFunction.accessorRef(classPrototypeRef: JsNameRef): JsNameRef? =
        when (visibility) {
            DescriptorVisibilities.PRIVATE -> null
            else -> JsNameRef(
                namer.getNameForMemberFunction(this),
                classPrototypeRef
            )
        }

    private fun ExportedClass.generateInnerClassAssignment(outerClass: ExportedClassLike): JsStatement {
        val innerClassRef = namer.getNameForStaticDeclaration(ir).makeRef()
        val outerClassRef = namer.getNameForStaticDeclaration(outerClass.ir).makeRef()
        val companionObject = ir.companionObject()
        val secondaryConstructors = members.filterIsInstanceAnd<ExportedFunction> { it.isStatic }
        val bindConstructor = JsName("__bind_constructor_", false)

        val blockStatements = mutableListOf<JsStatement>(
            JsVars(JsVars.JsVar(bindConstructor, innerClassRef.bindToThis()))
        )

        if (companionObject != null) {
            val companionName = companionObject.getJsNameOrKotlinName().identifier
            blockStatements.add(
                jsAssignment(
                    JsNameRef(companionName, bindConstructor.makeRef()),
                    JsNameRef(companionName, innerClassRef),
                ).makeStmt()
            )
        }

        secondaryConstructors.forEach {
            val currentFunRef = namer.getNameForStaticDeclaration(it.ir).makeRef()
            val assignment = jsAssignment(
                JsNameRef(it.name, bindConstructor.makeRef()),
                currentFunRef.bindToThis()
            ).makeStmt()

            blockStatements.add(assignment)
        }

        blockStatements.add(JsReturn(bindConstructor.makeRef()))

        return defineProperty(
            prototypeOf(outerClassRef),
            name,
            JsFunction(
                emptyScope,
                JsBlock(*blockStatements.toTypedArray()),
                "inner class '$name' getter"
            ),
            null
        ).makeStmt()
    }

    private fun JsNameRef.bindToThis(): JsInvocation {
        return JsInvocation(
            JsNameRef("bind", this),
            JsNullLiteral(),
            JsThisRef()
        )
    }

    private fun List<JsStatement>.wrapWithExportComment(header: String): List<JsStatement> {
        return listOf(JsSingleLineComment("export: $header")) + this + listOf(JsSingleLineComment("end export"))
    }
}
