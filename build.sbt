val scala3Version = "3.3.4"

lazy val root = project
  .in(file("."))
  .enablePlugins(JavaAppPackaging)
  .settings(
    name := "moin-melder",
    version := "0.1.0",
    scalaVersion := scala3Version,
    libraryDependencies ++= Seq(
      "dev.zio"                 %% "zio"                 % "2.1.14",
      "dev.zio"                 %% "zio-streams"         % "2.1.14",
      "dev.zio"                 %% "zio-json"            % "0.7.3",
      "dev.zio"                 %% "zio-logging"         % "2.4.0",
      "dev.zio"                 %% "zio-config"          % "4.0.3",
      "dev.zio"                 %% "zio-config-typesafe" % "4.0.3",
      "com.alphacephei"          % "vosk"                % "0.3.45",
      "net.java.dev.jna"         % "jna"                 % "5.7.0",
      "com.microsoft.playwright" % "playwright"          % "1.50.0",
    ),
    Compile / run / fork := true,
  )
