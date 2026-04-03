package link.infra.packwiz.installer.ui.gui

import link.infra.packwiz.installer.ui.IUserInterface
import link.infra.packwiz.installer.ui.IUserInterface.ExceptionListResult
import link.infra.packwiz.installer.ui.data.ExceptionDetails
import link.infra.packwiz.installer.ui.data.IOptionDetails
import link.infra.packwiz.installer.ui.data.InstallProgress
import link.infra.packwiz.installer.util.Log
import java.awt.EventQueue
import java.util.Timer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import javax.swing.JDialog
import javax.swing.JOptionPane
import javax.swing.UIManager
import kotlin.concurrent.timer
import kotlin.system.exitProcess

class GUIHandler : IUserInterface {
	private lateinit var frmPackwizlauncher: InstallWindow

	@Volatile
	override var optionsButtonPressed = false
		set(value) {
			optionalSelectedLatch.countDown()
			field = value
		}
	@Volatile
	override var cancelButtonPressed = false
		set(value) {
			optionalSelectedLatch.countDown()
			field = value
			cancelCallback?.invoke()
		}
	@Volatile
	override var cancelCallback: (() -> Unit)? = null
	var okButtonPressed = false
		set(value) {
			optionalSelectedLatch.countDown()
			field = value
		}
	@Volatile
	override var firstInstall = false

	override var title = "模组包更新器"
		set(value) {
			field = value
			EventQueue.invokeLater { frmPackwizlauncher.title = value }
		}

	init {
		EventQueue.invokeAndWait {
			try {
				UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
			} catch (e: Exception) {
				Log.warn("Failed to set look and feel", e)
			}
			frmPackwizlauncher = InstallWindow(this).apply {
				title = this@GUIHandler.title
			}
		}
	}

	private val visibleCountdownLatch = CountDownLatch(1)
	private val optionalSelectedLatch = CountDownLatch(1)

	override fun show() = EventQueue.invokeLater {
		frmPackwizlauncher.isVisible = true
		frmPackwizlauncher.toFront()
		frmPackwizlauncher.requestFocus()
		visibleCountdownLatch.countDown()
	}

	override fun dispose() = EventQueue.invokeAndWait {
		frmPackwizlauncher.dispose()
	}

	override fun showErrorAndExit(message: String, e: Exception?): Nothing {
		val buttons = arrayOf("退出", if (firstInstall) "不安装并继续" else "不更新并继续")
		if (e != null) {
			Log.fatal(message, e)
			EventQueue.invokeAndWait {
				val result = JOptionPane.showOptionDialog(frmPackwizlauncher,
					"$message: $e",
					title,
					JOptionPane.YES_NO_OPTION, JOptionPane.ERROR_MESSAGE, null, buttons, buttons[0])
				if (result == 1) {
					Log.info("User selected to continue without installing/updating, exiting with code 0...")
					exitProcess(0)
				} else {
					Log.info("User selected to quit, exiting with code 1...")
					exitProcess(1)
				}
			}
		} else {
			Log.fatal(message)
			EventQueue.invokeAndWait {
				val result = JOptionPane.showOptionDialog(frmPackwizlauncher,
					message,
					title,
					JOptionPane.YES_NO_OPTION, JOptionPane.ERROR_MESSAGE, null, buttons, buttons[0])
				if (result == 1) {
					Log.info("User selected to continue without installing/updating, exiting with code 0...")
					exitProcess(0)
				} else {
					Log.info("User selected to quit, exiting with code 1...")
					exitProcess(1)
				}
			}
		}
		exitProcess(1)
	}

	override fun submitProgress(progress: InstallProgress) {
		val sb = StringBuilder()
		if (progress.hasProgress) {
			sb.append('(')
			sb.append(progress.progress)
			sb.append('/')
			sb.append(progress.progressTotal)
			sb.append(") ")
		}
		sb.append(progress.message)
		Log.info(sb.toString())
		EventQueue.invokeLater {
			frmPackwizlauncher.displayProgress(progress)
		}
	}

	override fun showOptions(options: List<IOptionDetails>): Boolean {
		val future = CompletableFuture<Boolean>()
		EventQueue.invokeAndWait {
			if (options.isEmpty()) {
				JOptionPane.showMessageDialog(null,
					"此模组包没有可选模组。",
					"可选模组", JOptionPane.INFORMATION_MESSAGE)
				future.complete(false)
			} else {
				OptionsSelectWindow(options, future, frmPackwizlauncher).apply {
					defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE
					isVisible = true
				}
			}
		}
		return future.get()
	}

	override fun showExceptions(exceptions: List<ExceptionDetails>, numTotal: Int, allowsIgnore: Boolean): ExceptionListResult {
		val future = CompletableFuture<ExceptionListResult>()
		EventQueue.invokeLater {
			ExceptionListWindow(exceptions, future, numTotal, allowsIgnore, frmPackwizlauncher).apply {
				defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE
				isVisible = true
			}
		}
		return future.get()
	}

	override fun disableOptionsButton(hasOptions: Boolean) = EventQueue.invokeLater {
		frmPackwizlauncher.disableOptionsButton(hasOptions)
	}

	override fun showCancellationDialog(): IUserInterface.CancellationResult {
		val future = CompletableFuture<IUserInterface.CancellationResult>()
		EventQueue.invokeLater {
			val buttons = arrayOf("退出游戏", "忽略并启动")
			val result = JOptionPane.showOptionDialog(frmPackwizlauncher,
					"更新已取消。你希望退出游戏，还是忽略本次更新继续启动？",
					"更新已取消",
					JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE, null, buttons, buttons[0])
			future.complete(if (result == 0) IUserInterface.CancellationResult.QUIT else IUserInterface.CancellationResult.CONTINUE)
		}
		return future.get()
	}

	override fun showUpdateConfirmationDialog(oldVersions: List<Pair<String, String?>>, newVersions: List<Pair<String, String?>>): IUserInterface.UpdateConfirmationResult {
		assert(newVersions.isNotEmpty())
		val future = CompletableFuture<IUserInterface.UpdateConfirmationResult>()
		EventQueue.invokeLater {
			val oldVersIndex = oldVersions.map { it.first to it.second }.toMap()
			val newVersIndex = newVersions.map { it.first to it.second }.toMap()
			val message = StringBuilder()
			message.append("<html>" +
					"此模组包检测到以下组件版本更新：<br>" +
					"<ul>")

			for (oldVer in oldVersions) {
				val correspondingNewVer = newVersIndex[oldVer.first]
				message.append("<li>")
				message.append(oldVer.first.replaceFirstChar { it.uppercase() })
				message.append(": <font color=${if (oldVer.second != correspondingNewVer) "#ff0000" else "#000000"}>")
				message.append(oldVer.second ?: "未找到")
				message.append("</font></li>")
			}
			message.append("</ul>")

			message.append("新版本：" +
					"<ul>")
			for (newVer in newVersions) {
				val correspondingOldVer = oldVersIndex[newVer.first]
				message.append("<li>")
				message.append(newVer.first.replaceFirstChar { it.uppercase() })
				message.append(": <font color=${if (newVer.second != correspondingOldVer) "#00ff00" else "#000000"}>")
				message.append(newVer.second ?: "未找到")
				message.append("</font></li>")
			}
			message.append("</ul><br>" +
					"是否更新版本、直接启动（不更新），或取消启动？")


			val options = arrayOf("取消", "仍然继续", "立即更新")
			val result = JOptionPane.showOptionDialog(frmPackwizlauncher, message,
					"MultiMC 版本更新",
					JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[2])
			future.complete(
				when (result) {
					JOptionPane.CLOSED_OPTION, 0 -> IUserInterface.UpdateConfirmationResult.CANCELLED
					1 -> IUserInterface.UpdateConfirmationResult.CONTINUE
					2 -> IUserInterface.UpdateConfirmationResult.UPDATE
					else -> IUserInterface.UpdateConfirmationResult.CANCELLED
				}
			)
		}
		return future.get()
	}

	override fun awaitOptionalButton(showCancel: Boolean, timeout: Long) {
		EventQueue.invokeAndWait {
			frmPackwizlauncher.showOk(!showCancel)
		}
		visibleCountdownLatch.await()

		var closeTimer: Timer? = null
		if (timeout >= 0) {
			var count = 0
			closeTimer = timer("timeout", true, 0, 1000) {
				if (count >= timeout) {
					optionalSelectedLatch.countDown()
					cancel()
				} else {
					frmPackwizlauncher.timeoutOk(timeout - count)
					count += 1
				}
			};
		}

		optionalSelectedLatch.await()
		closeTimer?.cancel()
		EventQueue.invokeLater {
			frmPackwizlauncher.hideOk()
		}
	}
}
