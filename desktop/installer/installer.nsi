; PDF Master for Windows — installer (NSIS 3, Modern UI 2).
; Per-user install: no administrator rights needed. Registers PDF Master as a PDF app for "Open with" and Default apps.
Unicode true
!ifndef VERSION
  !define VERSION "1.1.0"
!endif
!define APP "PDF Master"
!define EXE "PDF Master.exe"
!define PROGID "PDFMaster.Document"
!define UNINST_KEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\PDFMaster"
!define CAPS_KEY "Software\PDF Master\Capabilities"

Name "${APP}"
OutFile "..\dist\PDF-Master-Setup-${VERSION}.exe"
InstallDir "$LOCALAPPDATA\Programs\PDF Master"
InstallDirRegKey HKCU "Software\PDF Master" "InstallDir"
RequestExecutionLevel user
SetCompressor /SOLID lzma
BrandingText "${APP} ${VERSION}"
VIProductVersion "${VERSION}.0"
VIAddVersionKey "ProductName" "${APP}"
VIAddVersionKey "FileDescription" "${APP} Setup"
VIAddVersionKey "FileVersion" "${VERSION}"
VIAddVersionKey "ProductVersion" "${VERSION}"
VIAddVersionKey "LegalCopyright" "${APP}"
VIAddVersionKey "CompanyName" "${APP}"

!include "MUI2.nsh"
!include "LogicLib.nsh"
!define MUI_ICON "..\build\icon.ico"
!define MUI_UNICON "..\build\icon.ico"
!define MUI_ABORTWARNING
!define MUI_WELCOMEPAGE_TITLE "Welcome to ${APP}"
!define MUI_WELCOMEPAGE_TEXT "Every PDF tool on your PC: merge, split, compress, convert, edit, sign, OCR in 15 languages and more.$\r$\n$\r$\nYour files are processed on this computer and never uploaded.$\r$\n$\r$\nClick Next to continue."
!define MUI_COMPONENTSPAGE_SMALLDESC
!define MUI_FINISHPAGE_RUN "$INSTDIR\${EXE}"
!define MUI_FINISHPAGE_RUN_TEXT "Open ${APP}"
!define MUI_FINISHPAGE_SHOWREADME ""
!define MUI_FINISHPAGE_SHOWREADME_TEXT "Choose ${APP} as the default app for PDF files"
!define MUI_FINISHPAGE_SHOWREADME_FUNCTION OpenDefaultApps

!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_COMPONENTS
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "English"

Function OpenDefaultApps
  ; Windows 10/11 let only the user pick the default app; this opens the right Settings page.
  ExecShell "open" "ms-settings:defaultapps?registeredAppUser=PDF%20Master"
FunctionEnd

Section "${APP} (required)" SecMain
  SectionIn RO
  SetOutPath "$INSTDIR"
  File /r "..\dist\win-unpacked\*.*"
  WriteUninstaller "$INSTDIR\Uninstall PDF Master.exe"
  WriteRegStr HKCU "Software\PDF Master" "InstallDir" "$INSTDIR"
  CreateShortCut "$SMPROGRAMS\${APP}.lnk" "$INSTDIR\${EXE}" "" "$INSTDIR\${EXE}" 0

  ; "Add or remove programs" entry
  WriteRegStr HKCU "${UNINST_KEY}" "DisplayName" "${APP}"
  WriteRegStr HKCU "${UNINST_KEY}" "DisplayVersion" "${VERSION}"
  WriteRegStr HKCU "${UNINST_KEY}" "Publisher" "${APP}"
  WriteRegStr HKCU "${UNINST_KEY}" "DisplayIcon" "$INSTDIR\${EXE}"
  WriteRegStr HKCU "${UNINST_KEY}" "InstallLocation" "$INSTDIR"
  WriteRegStr HKCU "${UNINST_KEY}" "URLInfoAbout" "https://shekathali.com/pdf/"
  WriteRegStr HKCU "${UNINST_KEY}" "UninstallString" '"$INSTDIR\Uninstall PDF Master.exe"'
  WriteRegDWORD HKCU "${UNINST_KEY}" "NoModify" 1
  WriteRegDWORD HKCU "${UNINST_KEY}" "NoRepair" 1
  WriteRegDWORD HKCU "${UNINST_KEY}" "EstimatedSize" 260000

  ; File type and "Open with" registration for PDF files
  WriteRegStr HKCU "Software\Classes\${PROGID}" "" "PDF Document"
  WriteRegStr HKCU "Software\Classes\${PROGID}" "FriendlyTypeName" "PDF Document"
  WriteRegStr HKCU "Software\Classes\${PROGID}\DefaultIcon" "" "$INSTDIR\resources\pdf-file.ico"
  WriteRegStr HKCU "Software\Classes\${PROGID}\shell" "" "open"
  WriteRegStr HKCU "Software\Classes\${PROGID}\shell\open" "FriendlyAppName" "${APP}"
  WriteRegStr HKCU "Software\Classes\${PROGID}\shell\open\command" "" '"$INSTDIR\${EXE}" "%1"'
  WriteRegStr HKCU "Software\Classes\.pdf\OpenWithProgids" "${PROGID}" ""
  WriteRegStr HKCU "Software\Classes\Applications\${EXE}" "FriendlyAppName" "${APP}"
  WriteRegStr HKCU "Software\Classes\Applications\${EXE}\SupportedTypes" ".pdf" ""
  WriteRegStr HKCU "Software\Classes\Applications\${EXE}\DefaultIcon" "" "$INSTDIR\resources\pdf-file.ico"
  WriteRegStr HKCU "Software\Classes\Applications\${EXE}\shell\open\command" "" '"$INSTDIR\${EXE}" "%1"'
  ; Listed under Settings > Apps > Default apps
  WriteRegStr HKCU "${CAPS_KEY}" "ApplicationName" "${APP}"
  WriteRegStr HKCU "${CAPS_KEY}" "ApplicationDescription" "Every PDF tool: view, edit, sign, convert, compress and OCR PDFs."
  WriteRegStr HKCU "${CAPS_KEY}" "ApplicationIcon" "$INSTDIR\${EXE},0"
  WriteRegStr HKCU "${CAPS_KEY}\FileAssociations" ".pdf" "${PROGID}"
  WriteRegStr HKCU "Software\RegisteredApplications" "${APP}" "${CAPS_KEY}"
  System::Call 'shell32::SHChangeNotify(i 0x08000000, i 0, p 0, p 0)'
SectionEnd

Section "Desktop shortcut" SecDesktop
  CreateShortCut "$DESKTOP\${APP}.lnk" "$INSTDIR\${EXE}" "" "$INSTDIR\${EXE}" 0
SectionEnd

Section "Use for PDF files when no other app is set" SecDefault
  ; Takes effect on PCs where no PDF app has been chosen yet; otherwise pick it on the last page.
  ReadRegStr $0 HKCU "Software\Classes\.pdf" ""
  ${If} $0 == ""
    WriteRegStr HKCU "Software\Classes\.pdf" "" "${PROGID}"
  ${EndIf}
  WriteRegStr HKCU "Software\Classes\.pdf" "Content Type" "application/pdf"
  System::Call 'shell32::SHChangeNotify(i 0x08000000, i 0, p 0, p 0)'
SectionEnd

!insertmacro MUI_FUNCTION_DESCRIPTION_BEGIN
  !insertmacro MUI_DESCRIPTION_TEXT ${SecMain} "The PDF Master app, Start Menu shortcut, and the $\"Open with$\" entry for PDF files."
  !insertmacro MUI_DESCRIPTION_TEXT ${SecDesktop} "Put a PDF Master shortcut on the desktop."
  !insertmacro MUI_DESCRIPTION_TEXT ${SecDefault} "Open PDFs in PDF Master by double-click if no other PDF app is set yet."
!insertmacro MUI_FUNCTION_DESCRIPTION_END

Section "Uninstall"
  Delete "$SMPROGRAMS\${APP}.lnk"
  Delete "$DESKTOP\${APP}.lnk"
  RMDir /r "$INSTDIR"
  DeleteRegKey HKCU "${UNINST_KEY}"
  DeleteRegKey HKCU "Software\Classes\${PROGID}"
  DeleteRegValue HKCU "Software\Classes\.pdf\OpenWithProgids" "${PROGID}"
  DeleteRegKey HKCU "Software\Classes\Applications\${EXE}"
  DeleteRegValue HKCU "Software\RegisteredApplications" "${APP}"
  DeleteRegKey HKCU "Software\PDF Master"
  ReadRegStr $0 HKCU "Software\Classes\.pdf" ""
  ${If} $0 == "${PROGID}"
    DeleteRegValue HKCU "Software\Classes\.pdf" ""
  ${EndIf}
  System::Call 'shell32::SHChangeNotify(i 0x08000000, i 0, p 0, p 0)'
SectionEnd
