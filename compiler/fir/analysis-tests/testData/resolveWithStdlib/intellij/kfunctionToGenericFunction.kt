import kotlin.math.sin

class LineChart<X : Number, Y : Number>

fun <X : Number, Y : Number> lineChart(ink: LineChart<X, Y>.() -> Unit) = LineChart<X, Y>().apply(ink)

fun <X : Number, Y : Number> LineChart<X, Y>.datasets(body: DataHolder<X, Y>.() -> Unit) {}

fun <X : Number, Y : Number> DataHolder<X, Y>.dataset(body: LineDataset<X, Y>.() -> Unit) {}

class DataHolder<X : Number, Y : Number>(val data: MutableList<LineDataset<X, Y>>)

class LineDataset<X : Number, Y : Number>

class Generator<X : Number, Y : Number> {
    lateinit var i: Iterable<X>
    lateinit var z: (x: X) -> Y
}

fun <X : Number, Y : Number> LineDataset<X, Y>.generate(generator: Generator<X, Y>.() -> Unit) {}

private fun funcXYLineChart() = lineChart<Double, Double> {
    datasets {
        // Generated values
        dataset {
            generate {
                i = listOf(0.0, 1.0, 2.0)
                z = ::sin
            }
        }
    }
}
