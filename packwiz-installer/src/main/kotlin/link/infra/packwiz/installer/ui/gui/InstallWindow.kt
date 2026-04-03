package link.infra.packwiz.installer.ui.gui

import link.infra.packwiz.installer.ui.data.InstallProgress
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.*
import javax.swing.border.EmptyBorder

class InstallWindow(private val handler: GUIHandler) : JFrame() {
	private var lblProgresslabel: JLabel
	private var lblReminder: JLabel
	private var progressBar: JProgressBar
	private var btnOptions: JButton
	private val btnCancel: JButton
	private val btnOk: JButton
	private val buttonsPanel: JPanel

	init {
		setBounds(100, 100, 680, 180)
		minimumSize = Dimension(680, 180)
		// Works better with tiling window managers - there isn't any reason to change window size currently anyway
		isResizable = false
		defaultCloseOperation = EXIT_ON_CLOSE
		isAlwaysOnTop = true
		setLocationRelativeTo(null)

		// Progress bar and loading text
		add(JPanel().apply {
			border = EmptyBorder(10, 10, 10, 10)
			layout = BorderLayout(0, 0)

			progressBar = JProgressBar().apply {
				isIndeterminate = true
				preferredSize = Dimension(440, 24)
				minimumSize = Dimension(320, 24)
				isStringPainted = true
				string = "准备中..."
			}
			add(progressBar, BorderLayout.CENTER)

			add(JPanel().apply {
				layout = BoxLayout(this, BoxLayout.Y_AXIS)

				lblProgresslabel = JLabel("正在加载...")
				add(lblProgresslabel)

				lblReminder = JLabel("<html><font color='#cc5500'>提示：更新期间请勿关闭窗口，更新完成后请重新打开游戏。</font></html>")
				add(lblReminder)
			}, BorderLayout.SOUTH)
		}, BorderLayout.CENTER)

		// Buttons
		buttonsPanel = JPanel().apply {
			border = EmptyBorder(0, 5, 0, 5)
			layout = GridBagLayout()

			btnOptions = JButton("可选模组...").apply {
				alignmentX = Component.CENTER_ALIGNMENT

				addActionListener {
					text = "加载中..."
					isEnabled = false
					handler.optionsButtonPressed = true
				}
			}
			add(btnOptions, GridBagConstraints().apply {
				gridx = 1
				gridy = 0
			})

			btnCancel = JButton("取消").apply {
				addActionListener {
					isEnabled = false
					handler.cancelButtonPressed = true
				}
			}
			add(btnCancel, GridBagConstraints().apply {
				gridx = 1
				gridy = 1
			})
		}

		btnOk = JButton("继续").apply {
			addActionListener {
				handler.okButtonPressed = true
			}
		}
		add(buttonsPanel, BorderLayout.EAST)
	}

	fun displayProgress(progress: InstallProgress) {
		if (progress.hasProgress) {
			progressBar.isIndeterminate = false
			progressBar.value = progress.progress
			progressBar.maximum = progress.progressTotal
			progressBar.string = "${progress.progress}/${progress.progressTotal}"
		} else {
			progressBar.isIndeterminate = true
			progressBar.value = 0
			progressBar.string = "处理中..."
		}
		lblProgresslabel.text = progress.message
	}

	fun disableOptionsButton(hasOptions: Boolean) {
		btnOptions.apply {
			text = if (hasOptions) { "可选模组..." } else { "无可选模组" }
 			isEnabled = false
		}
	}

	fun showOk(hideCancel: Boolean) {
		if (hideCancel) {
			buttonsPanel.add(btnOk, GridBagConstraints().apply {
				gridx = 1
				gridy = 1
			})
			buttonsPanel.remove(btnCancel)
		} else {
			buttonsPanel.add(btnOk, GridBagConstraints().apply {
				gridx = 0
				gridy = 1
			})
		}
		buttonsPanel.revalidate()
	}

	fun hideOk() {
		buttonsPanel.remove(btnOk)
		if (!buttonsPanel.components.contains(btnCancel)) {
			buttonsPanel.add(btnCancel, GridBagConstraints().apply {
				gridx = 1
				gridy = 1
			})
		}
		buttonsPanel.revalidate()
	}

	fun timeoutOk(remaining: Long) {
		btnOk.text = "继续 ($remaining)"
	}
}
