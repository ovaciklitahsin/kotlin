"use strict";
var foo = JS_TESTS.foo;
var producer = JS_TESTS.foo.producer;
var consumer = JS_TESTS.foo.consumer;
var ExportedType = JS_TESTS.foo.ExportedType;
function assert(condition) {
    if (!condition) {
        throw "Assertion failed";
    }
}
function box() {
    var nonExportedType = producer(42);
    var exportedType = new ExportedType(nonExportedType);
    assert(consumer(nonExportedType) == 42);
    exportedType.value = producer(24);
    assert(consumer(exportedType.value) == 24);
    assert(consumer(exportedType.increment(nonExportedType)) == 43);
    return "OK";
}
