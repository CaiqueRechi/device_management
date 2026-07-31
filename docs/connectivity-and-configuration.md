# Conectividade, Wi-Fi e configuração remota segura

## Configuração do build

A API e os metadados esperados do JWT são fornecidos por propriedades do Gradle:

```powershell
.\gradlew.bat assembleRelease `
  -PconfigurationApiBaseUrl=https://mdm.exemplo.com/ `
  -PserverJwtPublicKeyBase64=MIIBIjANBgkqh... `
  -PserverJwtIssuer=rechi-mdm-api `
  -PserverJwtAudience=rechi-mdm-device
```

`serverJwtPublicKeyBase64` deve conter uma chave pública RSA X.509 DER, codificada em Base64,
com pelo menos 2048 bits. A chave pública não é segredo. A chave privada correspondente deve
existir somente no serviço que emite os tokens.

Os valores versionados impedem chamadas e validações acidentais enquanto o ambiente real não
for configurado. Nenhum token, senha ou chave privada deve ser colocado no repositório.

## Contrato da API

O aplicativo cria um UUID na primeira execução, cifra-o localmente e faz:

```http
GET /api/v1/devices/{deviceId}/configuration
Accept: application/jwt
```

A resposta é um JWT compacto assinado com `RS256`:

```text
eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.<payload>.<assinatura>
```

Claims obrigatórias:

```json
{
  "iss": "rechi-mdm-api",
  "aud": "rechi-mdm-device",
  "sub": "UUID_DO_APLICATIVO",
  "firstConnectionDate": "2026-07-30",
  "url": "https://portal.exemplo.com/",
  "iat": 1785360000,
  "nbf": 1785360000,
  "exp": 1785360300,
  "jti": "IDENTIFICADOR_UNICO_DO_TOKEN"
}
```

Na primeira configuração, `firstConnectionDate` precisa corresponder à data corrente em UTC.
Nas respostas futuras, precisa corresponder à data vinculada e cifrada na primeira aceitação.
O backend deve gerar essa claim usando UTC. `sub` sempre precisa ser igual ao UUID enviado na
rota. Também são validados:

- algoritmo fixo `RS256`, sem fallback;
- assinatura com a chave pública configurada;
- emissor e audiência exatos;
- expiração, início de validade e data de emissão, com tolerância de relógio de 60 segundos;
- chave RSA com pelo menos 2048 bits;
- presença de `jti`;
- URL HTTPS, sem credenciais embutidas.

O JWT é assinado e autenticado, mas seu payload não é secreto. Não coloque dados confidenciais
nele. Se confidencialidade durante o transporte for necessária, ela já é fornecida por HTTPS.
Se o próprio token precisar ser opaco, a evolução correta é JWE, com um contrato de chaves
separado.

## Armazenamento

UUID, data da primeira conexão, URL ativa e último `jti` são cifrados com AES-256-GCM. A chave
é criada como não exportável no Android Keystore. Cada valor usa IV aleatório, tag de
autenticidade e o nome da preferência como AAD, impedindo troca de ciphertext entre campos.
SharedPreferences são excluídas de backup e transferência entre dispositivos.

Depois de uma reinicialização ou enquanto estiver offline, a URL previamente autenticada pode
continuar sendo usada. Uma nova URL só substitui a atual após um novo JWT válido.

## Rede e WebView

- Apenas HTTPS é permitido pelo Manifest e pela Network Security Configuration.
- Redirects HTTP da chamada de configuração são rejeitados.
- A resposta da API é limitada a 64 KiB.
- A WebView bloqueia HTTP, mixed content, acesso a arquivos, content providers, pop-ups,
  cookies de terceiros e navegações ou sub-recursos de hosts/portas diferentes da configuração
  autenticada. Assets em CDN exigirão uma allowlist explícita futura.
- WebView debugging fica desativado, o cache HTTP não é persistido e captura de tela é
  bloqueada por `FLAG_SECURE`.

## Conectividade

O monitor usa `ConnectivityManager.NetworkCallback`. Uma conexão só é considerada disponível
quando a rede ativa possui `NET_CAPABILITY_INTERNET` e `NET_CAPABILITY_VALIDATED`. Assim, Wi-Fi
sem saída válida, portal cativo e troca entre Wi-Fi e dados móveis atualizam o estado sem polling.

## Seleção de Wi-Fi e limitação conhecida

- Android 10+: usa `Settings.Panel.ACTION_WIFI`, o painel restrito oficial.
- Android 8 e 9: não existe painel restrito. O fallback é `Settings.ACTION_WIFI_SETTINGS`,
  que pode expor outras áreas dos Ajustes dependendo do fabricante.
- Como Device Owner, Ajustes entra temporariamente na allowlist do Lock Task. Ao retornar,
  as políticas e o modo imersivo são reaplicados.
- O app nunca lê ou armazena senhas de Wi-Fi.

Para eliminar o fallback mais permissivo no Android 8/9, redes devem ser provisionadas
corporativamente por QR, managed configurations, DPC completo ou Android Management API.
