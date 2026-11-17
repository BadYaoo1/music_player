; Inno Setup script for plain bmusic player
; Builds an installer from the jpackage-produced app-image directory.

#define MyAppName "plain bmusic player"
#define MyAppVersion "1.0.0"
#define MyAppExeName "PlainBMusicPlayer.exe"
#define MyAppPublisher "plain-bmusic"
#define MyAppExeSource "out\PlainBMusicPlayer"
#define MyAppIcon "app_icon.ico"

[Setup]
AppId={{2E0B15A8-5E4D-4F3A-9B2C-4D8E1F2A3B4C}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
DefaultDirName={autopf}\PlainBMusicPlayer
DefaultGroupName=plain bmusic player
OutputDir=out
OutputBaseFilename=PlainBMusicPlayer-Setup-{#MyAppVersion}
SetupIconFile={#MyAppIcon}
Compression=lzma2/ultra
SolidCompression=yes
WizardStyle=modern
PrivilegesRequired=lowest
UninstallDisplayIcon={app}\{#MyAppExeName}
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible

[Languages]
Name: "chinesesimplified"; MessagesFile: "compiler:Languages\ChineseSimplified.isl"
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Additional icons:"; Flags: unchecked

[Files]
; 整个 app-image 目录原样拷进去，JRE + 应用 + node.exe + lyric-bridge.js 全在里面
Source: "{#MyAppExeSource}\*"; DestDir: "{app}"; Flags: recursesubdirs createallsubdirs ignoreversion

[Icons]
Name: "{group}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; IconFilename: "{app}\app_icon.ico"
Name: "{group}\Uninstall {#MyAppName}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; IconFilename: "{app}\app_icon.ico"; Tasks: desktopicon

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "Run {#MyAppName}"; Flags: nowait postinstall skipifsilent
