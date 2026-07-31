# Revisão de segurança

Revisão realizada sobre o código do MVP após a integração do JWT.

## Controles implementados

- JWT aceito somente com `alg=RS256`, chave RSA de 2048 bits ou maior e assinatura válida.
- Validação estrita de `iss`, `aud`, `sub`, `exp`, `nbf`, `iat`, `jti` e
  `firstConnectionDate`.
- Associação do token ao UUID aleatório da instalação, evitando IMEI e serial de hardware.
- Data da primeira conexão cifrada e comparada nas validações futuras.
- AES-256-GCM com chave não exportável do Android Keystore e AAD por campo.
- Dados seguros excluídos de backup e transferência.
- HTTPS obrigatório, cleartext e redirects da API bloqueados.
- Limite de tamanho para respostas.
- URL autenticada antes de persistir ou abrir.
- WebView limitada a HTTPS, host e porta autenticados, inclusive para sub-recursos.
- Acesso a arquivos, content providers, mixed content, pop-ups, cookies de terceiros,
  debugging e cache HTTP desativados.
- Capturas de tela bloqueadas e release com shrinking/obfuscation.
- Device Owner exige horário automático e bloqueia alteração manual quando suportado.

## Riscos residuais

### Painel Wi-Fi no Android 8 e 9 — médio

Essas versões não possuem painel Wi-Fi restrito. O fallback oficial abre Ajustes e pode
permitir navegação adicional em ROMs de fabricantes. Mitigação definitiva: provisionar redes
por DPC/QR/Android Management API ou elevar o `minSdk` para 29.

### Sem certificate pinning — médio

HTTPS usa a cadeia de confiança do Android. Uma autoridade certificadora comprometida ou uma
CA corporativa instalada no aparelho poderia interceptar tráfego, mas não criar uma
configuração válida sem a chave privada do JWT. Pinning pode reduzir esse risco, porém exige
plano de rotação e pins de backup.

### JavaScript e DOM storage na WebView — médio

São necessários para o portal, mas ampliam o impacto de XSS ou comprometimento do host
autenticado. O portal deve usar CSP estrita, cookies `Secure`, `HttpOnly` e `SameSite`, evitar
bridges JavaScript nativas e manter suas dependências atualizadas.

### Provisionamento inicial do UUID — médio

O UUID identifica a instalação, mas não comprova hardware genuíno. O backend deve cadastrar
previamente os UUIDs autorizados e nunca emitir JWT para identificadores desconhecidos.
Uma evolução pode registrar uma chave assimétrica do Android Keystore com key attestation ou
usar Play Integrity.

### Relógio do dispositivo — baixo

A primeira ativação depende da data UTC local, além de `iat`, `nbf` e `exp`. Device Owner exige
horário automático, mas falhas de sincronização podem causar rejeição legítima. O aplicativo
falha fechado; o backend deve usar UTC e janelas curtas.

### Rotação da chave de assinatura — baixo

O MVP aceita uma única chave pública de build e não seleciona chaves por `kid`. A rotação exige
uma atualização coordenada do APK. Futuramente, manter duas chaves públicas durante a janela de
rotação e exigir um `kid` conhecido.

### Replay durante a validade — baixo

Um JWT capturado pode ser reapresentado enquanto estiver dentro de `nbf/exp`; HTTPS reduz a
possibilidade de captura. Como o token é uma configuração assinada vinculada ao UUID, o impacto
é restaurar a mesma configuração. Se cada resposta precisar ser consumida uma única vez, o
backend deve controlar `jti`.

## Observação sobre “JWT criptografado”

JWT assinado (JWS) garante autoria e integridade, mas o payload continua legível. Neste projeto,
o transporte é cifrado por TLS e os dados em repouso por AES-GCM. Se houver requisito de ocultar
o payload do próprio JWT fora do TLS, deve-se adicionar JWE; não se deve inventar uma cifra
própria ou colocar um segredo simétrico dentro do APK.
