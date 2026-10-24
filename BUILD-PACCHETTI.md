# Pacchetti MeteoBetti

Questi pacchetti includono il runtime Java: chi li usa non deve installare Java.
Per prepararli serve invece un JDK 25 con `javac`, `jar` e `jpackage` disponibili nel `PATH`.

Ogni pacchetto va creato sul sistema operativo di destinazione:

- **macOS:** esegui `bash build-macos-app.sh`. Il DMG viene creato in
  `build/macos/dist/MeteoBettiUltimateOS-macos.dmg`.
- **Windows:** apri PowerShell nella cartella del progetto ed esegui
  `.\build-windows.ps1`. Estrai lo ZIP creato in `build\windows\dist` e avvia
  `MeteoBettiUltimateOS\MeteoBettiUltimateOS.exe`.
- **Linux:** esegui `bash build-linux.sh`. Estrai il TAR.GZ creato in
  `build/linux/dist` e avvia `MeteoBettiUltimateOS/bin/MeteoBettiUltimateOS`.

Windows e Linux non si possono compilare in modo affidabile da macOS con questi
script: occorre copiarvi la cartella del progetto ed eseguire lo script sul
rispettivo sistema. I pacchetti Windows e Linux sono archivi portabili, non
installer MSI/EXE o DEB/RPM.

## Pubblicazione automatica

Il workflow `.github/workflows/release.yml` compila i tre pacchetti su runner
macOS, Windows e Linux, con versioni macOS per Apple Silicon e Intel. Per usarlo,
il progetto deve essere in un repository GitHub. Dopo aver caricato il codice e
i file del progetto su GitHub, crea e pubblica un tag versione, per esempio
`v1.0.0`: il workflow prepara i pacchetti e li allega a una GitHub Release
scaricabile. Da **Actions** puoi anche avviare il workflow manualmente per
verificare le build; in quel caso i pacchetti restano disponibili come artefatti
temporanei per 14 giorni.

Il workflow crea pacchetti non firmati con certificati di distribuzione.
Per una distribuzione macOS senza avvisi di sicurezza servono firma Developer
ID e notarizzazione Apple; per Windows può essere utile la firma Authenticode.
Questi passaggi richiedono certificati e credenziali del proprietario e non
sono configurati automaticamente.

Per rendere le release visibili a tutti, il repository GitHub deve essere
pubblico. Se non hai ancora un repository, creane uno su GitHub, carica i file
del progetto (il file `.gitignore` esclude i grandi file temporanei in `build`)
e poi crea un tag, per esempio `v1.0.0`. Non caricare credenziali o certificati
nel repository.

## Privacy e verifiche

Se il servizio di posizione del sistema non è disponibile o viene negato,
MeteoBetti non cerca più di indovinare la città tramite l'indirizzo IP e non
mostra una località predefinita errata: chiede di cercare una città. Le richieste
meteo e cartografiche accettano solo HTTPS verso i servizi previsti, non seguono
redirect e limitano la dimensione delle risposte e delle immagini radar.
Il nome scelto viene salvato nelle preferenze locali del sistema.

Il workflow esegue anche controlli automatici su connessioni sicure, nomi salvati
e dimensioni delle immagini prima di creare i pacchetti. Le azioni GitHub sono
fissate a revisioni specifiche e Dependabot controlla gli aggiornamenti. Prima
di una distribuzione pubblica, occorre comunque verificare i pacchetti su
macOS, Windows e Linux reali e configurare firma/notarizzazione con certificati
personali. Queste misure riducono rischi noti, ma non costituiscono una
certificazione o garanzia assoluta di sicurezza.
