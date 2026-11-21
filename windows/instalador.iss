[Setup]
AppId={{6F1B2C9A-3D4E-4F11-9A7B-A1B2C3D4E5F6}
AppName=Controle Remoto
AppVersion=1.1
AppPublisher=Marcos Calebe
DefaultDirName={autopf}\Controle Remoto
DefaultGroupName=Controle Remoto
OutputDir=..\saida
OutputBaseFilename=ControleRemoto-Instalador-Windows
Compression=lzma2
SolidCompression=yes
PrivilegesRequired=admin
ArchitecturesInstallIn64BitMode=x64compatible
UninstallDisplayIcon={app}\ControleRemoto.exe
WizardStyle=modern

[Languages]
Name: "pt"; MessagesFile: "compiler:Languages\BrazilianPortuguese.isl"

[Tasks]
Name: "desktopicon"; Description: "Criar atalho na área de trabalho"
Name: "startup"; Description: "Abrir automaticamente quando o Windows iniciar"

[Files]
Source: "..\dist\ControleRemoto.exe"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{group}\Controle Remoto"; Filename: "{app}\ControleRemoto.exe"
Name: "{group}\Desinstalar Controle Remoto"; Filename: "{uninstallexe}"
Name: "{autodesktop}\Controle Remoto"; Filename: "{app}\ControleRemoto.exe"; Tasks: desktopicon
Name: "{commonstartup}\Controle Remoto"; Filename: "{app}\ControleRemoto.exe"; Tasks: startup

[Run]
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""Controle Remoto"""; Flags: runhidden
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall add rule name=""Controle Remoto"" dir=in action=allow program=""{app}\ControleRemoto.exe"" enable=yes profile=any"; Flags: runhidden
Filename: "{app}\ControleRemoto.exe"; Description: "Abrir o Controle Remoto agora"; Flags: nowait postinstall skipifsilent

[UninstallRun]
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""Controle Remoto"""; Flags: runhidden; RunOnceId: "fw"
Filename: "{sys}\taskkill.exe"; Parameters: "/f /im ControleRemoto.exe"; Flags: runhidden; RunOnceId: "kill"
