package app.veilark.macos

import java.awt.GraphicsEnvironment
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JOptionPane
import javax.swing.SwingUtilities

/** Last-resort visibility for an uncaught JVM/UI error; exception messages may contain imported secrets. */
internal object StartupDiagnostics {
  private val showing = AtomicBoolean(false)

  fun install() {
    Thread.setDefaultUncaughtExceptionHandler { _, failure ->
      record(failure, "uncaught")
      if (!GraphicsEnvironment.isHeadless() && showing.compareAndSet(false, true)) {
        SwingUtilities.invokeLater {
          try {
            val russian = Locale.getDefault().language == "ru"
            JOptionPane.showMessageDialog(null,
              if (russian) "Veilark столкнулся с ошибкой. Перезапустите приложение. Технические сведения: Library/Logs/Veilark/startup.log."
              else "Veilark encountered an error. Restart the app. Technical details: Library/Logs/Veilark/startup.log.",
              "Veilark", JOptionPane.ERROR_MESSAGE)
          } finally { showing.set(false) }
        }
      }
    }
  }

  @Synchronized fun record(failure: Throwable, stage: String) {
    runCatching {
      val directory = File(System.getProperty("user.home"), "Library/Logs/Veilark")
      directory.mkdirs()
      val file = File(directory, "startup.log")
      if (file.length() > 65_536) file.writeText("")
      val safeStage = stage.filter { it.isLetterOrDigit() || it == '-' }.take(32)
      file.appendText("stage=$safeStage\n" + safeRecord(failure) + "\n")
    }
  }

  internal fun safeRecord(failure: Throwable): String = buildString {
    appendLine(failure.javaClass.name)
    failure.stackTrace.take(16).forEach { frame ->
      appendLine("${frame.className}.${frame.methodName}:${frame.lineNumber}")
    }
  }.take(4_096)
}
