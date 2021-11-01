
import foo = JS_TESTS.foo;
import producer = JS_TESTS.foo.producer;
import consumer = JS_TESTS.foo.consumer;
import ExportedType = JS_TESTS.foo.ExportedType;

function assert(condition: boolean) {
    if (!condition) {
        throw "Assertion failed";
    }
}

function box(): string {
    const nonExportedType = producer(42)
    const exportedType = new ExportedType(nonExportedType)

    assert(consumer(nonExportedType) == 42)

    exportedType.value = producer(24)
    assert(consumer(exportedType.value) == 24)
    assert(consumer(exportedType.increment(nonExportedType)) == 43)
    return "OK";
}