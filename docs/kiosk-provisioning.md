# Provisionamento de quiosque

Este app usa as APIs Android de Device Policy Controller (DPC) para permitir Lock Task Mode.

A versao atual recebe da API a URL ativa do dispositivo e a preserva localmente.
Qualquer tentativa de navegar para outro esquema ou host e redirecionada de volta para a URL
configurada. Veja `connectivity-and-configuration.md` para o contrato da API.

## Requisitos

- Android 8.0+ recomendado
- Dispositivo de teste sem contas configuradas
- Preferencialmente aparelho resetado de fabrica
- ADB habilitado para provisionamento local
- Build instalado no dispositivo

## Configurar como Device Owner via ADB

Com o app instalado, execute:

```bash
adb shell dpm set-device-owner br.com.rechi.mobile/br.com.rechi.mobile.admin.RechiDeviceAdminReceiver
```

Depois abra o app. Ele vai aplicar as politicas iniciais e tentar entrar em modo quiosque.

## Verificar estado

```bash
adb shell dumpsys device_policy
```

Procure por `Device Owner` e pelo pacote `br.com.rechi.mobile`.

## Remover Device Owner em ambiente de desenvolvimento

```bash
adb shell dpm remove-active-admin br.com.rechi.mobile/br.com.rechi.mobile.admin.RechiDeviceAdminReceiver
```

Em alguns aparelhos, quando o Device Owner foi provisionado em modo mais restrito, pode ser necessario resetar o dispositivo.

## Atualizar APK debug

Como o APK debug e marcado como `testOnly`, instale com:

```bash
adb install -t -r app/build/outputs/apk/debug/app-debug.apk
```

## Observacoes importantes

- Lock Task Mode real exige que o pacote esteja autorizado por `DevicePolicyManager.setLockTaskPackages()`.
- `android:lockTaskMode="if_whitelisted"` permite que a Activity entre automaticamente no modo quando o pacote esta autorizado.
- Recursos como barra de status, keyguard e navegacao dependem da versao do Android e do fabricante.
- Para producao, o fluxo ideal costuma ser QR provisioning ou Android Management API, nao ADB manual.
