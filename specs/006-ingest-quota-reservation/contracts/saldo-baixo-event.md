# Contrato Normativo — Evento de Notificação `SALDO_BAIXO`

**Status**: aprovado para implementação no Core.

## Quando emitir

Após `confirm` que efetivamente debita uma reserva limitada, o Core calcula o saldo restante
do ciclo. Ao cruzar 20% ou 5% da franquia, publica um único evento por
`tenantId+productId+cycleStart+threshold`. A confirmação repetida não cria nova emissão.
Um `refund` posterior não remove, restabelece nem reemite uma chave `SALDO_BAIXO` já emitida
no ciclo.

## Envelope conceitual

```json
{
  "type": "SALDO_BAIXO",
  "severity": "MEDIUM",
  "tenantId": "identificador-do-tenant",
  "productId": "identificador-do-produto",
  "cycleStart": "2026-11-01T00:00:00Z",
  "threshold": 20,
  "remainingBytes": 200000000,
  "mandatoryRoles": ["DONO", "ADMINISTRADOR"],
  "mandatoryChannels": ["WEB", "EMAIL"],
  "idempotencyKey": "tenantId+productId+cycleStart+threshold"
}
```

Não incluir tokens, dados do lote ou outro segredo no evento.

## Entrega e limite de escopo

O Core persiste a intenção em outbox Mongo e publica no stream de notificações por uma porta
de publicação; o adaptador planejado usa Redis Streams já disponível no Core. Resolver a
matriz de notificações e entregar WEB/E-MAIL é responsabilidade do módulo Notificação, fora
destes repositórios.
