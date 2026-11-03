package org.lantai.infrastructure.runtime.install

interface RuntimeInstaller {
  val kind: String

  val displayName: String

  fun detect(): RuntimeDetection

  fun describe(): RuntimePackageInfo

  fun accept(
    bytes: ByteArray,
    fileName: String?,
  ): RuntimePackage

  fun install(installed: RuntimePackage): RuntimeProgress

  fun pull(): RuntimeProgress

  fun uninstall(purgeData: Boolean): RuntimeUninstallResult

  fun steps(): List<RuntimeStep>

  fun progress(): RuntimeProgress
}
