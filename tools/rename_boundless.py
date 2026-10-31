import pathlib, re

# App label and in-app copy.
for path in ["app/src/main/res/values/strings.xml", "app/src/main/res/values-ru/strings.xml"]:
    p = pathlib.Path(path)
    s = p.read_text(encoding="utf-8")
    s = s.replace('>Painter</string>', '>Boundless Paint</string>')
    s = s.replace('>Painter %1$s</string>', '>Boundless Paint %1$s</string>')
    s = s.replace('Painter', 'Boundless Paint')
    p.write_text(s, encoding="utf-8", newline="\n")

# README identity and links.
p = pathlib.Path("README.md")
s = p.read_text(encoding="utf-8")
s = s.replace("# Painter\n", "# Boundless Paint\n", 1)
s = s.replace("IWatman/Painter", "IWatman/Boundless-Paint")
s = s.replace("`painter-X.Y.Z.apk`", "`boundless-paint-X.Y.Z.apk`")
p.write_text(s, encoding="utf-8", newline="\n")

# Gradle root project name.
p = pathlib.Path("settings.gradle.kts")
s = p.read_text(encoding="utf-8")
s = s.replace('rootProject.name = "Painter"', 'rootProject.name = "Boundless Paint"')
p.write_text(s, encoding="utf-8", newline="\n")

# Updater repo.
p = pathlib.Path("app/build.gradle.kts")
s = p.read_text(encoding="utf-8")
s = s.replace('"IWatman/Painter"', '"IWatman/Boundless-Paint"')
s = s.replace('?: "0.4.4"', '?: "0.4.5"')
p.write_text(s, encoding="utf-8", newline="\n")

# Release asset name.
p = pathlib.Path(".github/workflows/release.yml")
s = p.read_text(encoding="utf-8")
s = s.replace('apk="painter-${GITHUB_REF_NAME#v}.apk"', 'apk="boundless-paint-${GITHUB_REF_NAME#v}.apk"')
p.write_text(s, encoding="utf-8", newline="\n")

# User agents.
for path in ["app/src/main/kotlin/app/painter/update/UpdateChecker.kt", "app/src/main/kotlin/app/painter/update/UpdateInstaller.kt"]:
    p = pathlib.Path(path)
    s = p.read_text(encoding="utf-8")
    s = s.replace('"Painter-Android"', '"BoundlessPaint-Android"')
    s = s.replace('"painter-update"', '"boundless-paint-update"')
    p.write_text(s, encoding="utf-8", newline="\n")
print("renamed")
