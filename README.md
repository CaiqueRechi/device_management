# rechi-mobile

Aplicativo Android nativo para a parte mobile do projeto Rechi.

Esta primeira base foca em MDM Android para dispositivos dedicados, com suporte inicial a modo quiosque usando Device Owner e Lock Task Mode.

## Stack inicial

- Android nativo em Kotlin
- Android Gradle Plugin 9.3.0
- Kotlin embutido no Android Gradle Plugin
- Compile SDK 36.1
- Minimum SDK 26
- Gradle 9.5.0 recomendado
- JDK 17 obrigatorio para build

## Estrutura

```text
rechi-mobile/
  app/
    src/main/
      java/br/com/rechi/mobile/
        MainActivity.kt
        admin/RechiDeviceAdminReceiver.kt
        kiosk/KioskPolicyController.kt
      res/
        values/
        xml/
  docs/
    kiosk-provisioning.md
```

## Como abrir

Abra a pasta `rechi-mobile` no Android Studio.

O SDK local esta configurado em `local.properties`. Esse arquivo fica fora do Git porque cada maquina costuma ter um caminho diferente.

Veja tambem [docs/dev-setup.md](docs/dev-setup.md).

Se ainda nao houver Gradle Wrapper no projeto, gere o wrapper pelo Android Studio ou depois de instalar o Gradle:

```bash
gradle wrapper --gradle-version 9.5.0
```

Depois disso, o build esperado e:

```bash
./gradlew assembleDebug
```

No Windows PowerShell:

```powershell
.\gradlew.bat assembleDebug
```

## Modo quiosque

O app so consegue travar o dispositivo de forma real quando esta configurado como Device Owner em um dispositivo corporativo/dedicado. Em um aparelho comum, `startLockTask()` pode cair em screen pinning, que o usuario consegue sair manualmente.

Veja o passo a passo em [docs/kiosk-provisioning.md](docs/kiosk-provisioning.md).
