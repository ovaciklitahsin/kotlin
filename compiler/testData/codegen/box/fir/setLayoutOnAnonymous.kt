// TARGET_BACKEND: JVM_IR
// WITH_RUNTIME
// MODULE: m1
// FILE: LayoutManager.java

public interface LayoutManager {
    String getName();
}

// FILE: OkLayout.java

public class OkLayout implements LayoutManager {
    public String getName() { return "OK"; }
}

// FILE: Container.java

public class Container {
    private LayoutManager layout = null;

    public LayoutManager getLayout() { return layout; }

    public void setLayout(LayoutManager value) { layout = value; }
}

// FILE: JPanel.java

public class JPanel extends Container {

}

// MODULE: m2(m1)
// FILE: ButtonPanel.kt

abstract class ButtonPanel : JPanel()

// FILE: box.kt

class Some {
    private val bar = foo()

    val baz get() = bar.layout

    companion object {
        fun foo() = object : ButtonPanel() {
            init {
                layout = OkLayout()
            }
        }
    }
}

fun box() = Some().baz.name
