1) SERVER: sav sadržaj mape server/ uploadaj na atspace u mapu /tv/ (preko File Managera).
   U config.php promijeni ADMIN_PASSWORD. Panel: http://TVOJA-DOMENA.atspace.cc/tv/admin.php
2) APK: u android/app/src/main/java/com/mojtv/app/Config.kt upiši svoju adresu (SERVER_URL).
   Build: Android Studio -> Open mapu android -> Build > Build APK
   ILI bez instalacije: uploadaj sadržaj mape android/ na GitHub repo -> Actions -> Build APK -> skini artifact.
