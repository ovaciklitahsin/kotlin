declare namespace JS_TESTS {
    type Nullable<T> = T | null | undefined
    namespace foo {
        function producer(value: number): any/* foo.NonExportedType */;
        function consumer(value: any/* foo.NonExportedType */): number;
        class ExportedType {
            constructor(value: any/* foo.NonExportedType */);
            value: any/* foo.NonExportedType */;
            increment<T>(t: T): any/* foo.NonExportedType */;
        }
    }
}
