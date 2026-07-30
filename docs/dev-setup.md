# Setup de desenvolvimento

## 1. Abrir no Android Studio

Abra a pasta:

```text
C:\Users\caique.mehret\Documents\Ibiporã\rechi-mobile
```

## 2. Conferir JDK

No Android Studio, abra:

```text
Settings > Build, Execution, Deployment > Build Tools > Gradle
```

Em `Gradle JDK`, selecione a JDK embutida do Android Studio ou qualquer JDK 17+.

## 3. Conferir SDK

No Android Studio, abra:

```text
Settings > Languages & Frameworks > Android SDK
```

Itens esperados nesta maquina:

- Android SDK Platform 36.1
- Android SDK Build-Tools 36.0.0
- Android SDK Platform-Tools

## 4. Gerar Gradle Wrapper

Se o Android Studio pedir o Gradle Wrapper, use o terminal dentro da pasta do projeto:

```powershell
gradle wrapper --gradle-version 9.5.0
```

Depois disso, o projeto deve ter:

```text
gradlew
gradlew.bat
gradle/wrapper/gradle-wrapper.jar
gradle/wrapper/gradle-wrapper.properties
```

## 5. Build local

```powershell
.\gradlew.bat assembleDebug
```

## 6. Teste em dispositivo

Instale o APK debug e configure Device Owner em um aparelho de testes resetado:

```powershell
adb shell dpm set-device-owner br.com.rechi.mobile/br.com.rechi.mobile.admin.RechiDeviceAdminReceiver
```
