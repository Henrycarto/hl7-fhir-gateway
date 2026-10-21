# Records docs/demo.gif with VHS (https://github.com/charmbracelet/vhs).
# The gateway and the FHIR server must already be running; see the handover notes.
Output docs/demo.gif

Set Shell "bash"
Set FontSize 15
Set Width 1100
Set Height 780
Set Padding 20
Set Theme "Github"
Set TypingSpeed 40ms

Hide
Type "export DEMO_PAUSE=1.2 PS1='$ ' && clear"
Enter
Show

Type "./scripts/demo.sh"
Sleep 500ms
Enter
Sleep 45s
