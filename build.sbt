Global / onChangedBuildSource := ReloadOnSourceChanges

val root = project
  .in(file("."))
  .settings(
    scalaVersion := "2.13.7",
    libraryDependencies ++= Seq(
      "com.disneystreaming.smithy4s" %% "smithy4s-http4s" % smithy4sVersion.value,
      "com.disneystreaming.smithy4s" %% "smithy4s-codegen-cli" % smithy4sVersion.value,
      "com.disneystreaming.smithy4s" %% "smithy4s-dynamic" % smithy4sVersion.value
    ),
    scalacOptions --= Seq("-Xfatal-warnings")
  )
  .enablePlugins(Smithy4sCodegenPlugin)
