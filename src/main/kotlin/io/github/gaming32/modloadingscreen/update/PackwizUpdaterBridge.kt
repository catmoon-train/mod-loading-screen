package io.github.gaming32.modloadingscreen.update

import link.infra.packwiz.installer.UpdateManager
import link.infra.packwiz.installer.target.Side
import link.infra.packwiz.installer.target.path.HttpUrlPath
import link.infra.packwiz.installer.target.path.PackwizFilePath
import link.infra.packwiz.installer.ui.IUserInterface
import link.infra.packwiz.installer.ui.data.ExceptionDetails
import link.infra.packwiz.installer.ui.data.IOptionDetails
import link.infra.packwiz.installer.ui.data.InstallProgress
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.Path.Companion.toOkioPath
import java.nio.file.Path

object PackwizUpdaterBridge {
    @JvmStatic
    fun runUpdate(gameDir: Path, packwizUrl: String) {
        val httpUrl = packwizUrl.toHttpUrl()
        val packFolder = PackwizFilePath(gameDir.toOkioPath())
        val multimcFolder = PackwizFilePath((gameDir.parent ?: gameDir).toOkioPath())
        val manifestFile = packFolder / "packwiz.json"
        val packFile = HttpUrlPath(httpUrl.resolve(".")!!, httpUrl.pathSegments.last())
        val options = UpdateManager.Options(
            packFile,
            manifestFile,
            packFolder,
            multimcFolder,
            Side.CLIENT,
            10L
        )

        UpdateManager(options, ModLoadingScreenPackwizUi(gameDir))
    }

    private class ModLoadingScreenPackwizUi(private val gameDir: Path) : IUserInterface {
        @Volatile
        override var optionsButtonPressed = false

        @Volatile
        override var cancelButtonPressed = false

        @Volatile
        override var cancelCallback: (() -> Unit)? = null

        @Volatile
        override var firstInstall = false

        override var title: String = "Mod Loading Screen Updater"

        override fun show() {
        }

        override fun dispose() {
        }

        override fun showErrorAndExit(message: String, e: Exception?): Nothing {
            StartupUpdateCoordinator.reportPackwizFailure(gameDir, message, e)
            throw PackwizUpdateException(message, e)
        }

        override fun submitProgress(progress: InstallProgress) {
            StartupUpdateCoordinator.reportPackwizProgress(
                gameDir,
                progress.message,
                progress.hasProgress,
                progress.progress,
                progress.progressTotal
            )
        }

        override fun showOptions(options: List<IOptionDetails>): Boolean {
            StartupUpdateCoordinator.reportPackwizNotice(
                gameDir,
                "Optional mods were detected; keeping recorded/default selections without showing a second UI"
            )
            return false
        }

        override fun showExceptions(
            exceptions: List<ExceptionDetails>,
            numTotal: Int,
            allowsIgnore: Boolean
        ): IUserInterface.ExceptionListResult {
            val message = buildString {
                append("Packwiz encountered ")
                append(exceptions.size)
                append(" failure(s) while processing ")
                append(numTotal)
                append(" task(s)")
            }
            val cause = exceptions.firstOrNull()?.exception
            StartupUpdateCoordinator.reportPackwizFailure(gameDir, message, cause)
            throw PackwizUpdateException(message, cause)
        }

        override fun disableOptionsButton(hasOptions: Boolean) {
        }

        override fun showCancellationDialog(): IUserInterface.CancellationResult {
            return IUserInterface.CancellationResult.QUIT
        }

        override fun showUpdateConfirmationDialog(
            oldVersions: List<Pair<String, String?>>, 
            newVersions: List<Pair<String, String?>>
        ): IUserInterface.UpdateConfirmationResult {
            return IUserInterface.UpdateConfirmationResult.UPDATE
        }

        override fun awaitOptionalButton(showCancel: Boolean, timeout: Long) {
        }
    }

    private class PackwizUpdateException(message: String, cause: Throwable?) : RuntimeException(message, cause)
}