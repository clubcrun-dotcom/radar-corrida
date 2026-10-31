# Radar de Corrida — v0.3 (protótipo de campo)

App Android que lê a oferta de corrida da Uber, 99 ou inDrive no momento em que ela aparece,
calcula **R$/km, R$/hora, custo e lucro** e mostra uma etiqueta colorida por cima da tela:

| Cor | Rótulo | Regra |
|---|---|---|
| Verde | ACEITAR | R$/km e R$/h acima das duas metas |
| Amarelo | AVALIAR | uma meta batida e a outra a ≥ 85% dela |
| Vermelho | RECUSAR | o resto |

O app **não clica em nada**: a etiqueta deixa os toques passarem para o app de baixo, e a
decisão é sempre do motorista. Também não usa internet; tudo fica no aparelho.

## Como funciona

```
Uber/99/inDrive desenha a oferta
      │  (evento de acessibilidade: "conteúdo da janela mudou")
      ▼
RadarService ── lê os textos das janelas do app de corrida (só dos apps da lista)
      ▼
OfferParser ── acha "R$ 23,94", "5 min (1,8 km)", "Viagem de 23 min (13,3 km)"
      ▼
Calculator ── km total, R$/km, R$/h, custo = (combustível ÷ km/l + extra) × km, lucro
      ▼
OverlayCard ── etiqueta verde/amarela/vermelha + linha no corridas.csv
```

Código do cálculo: `app/src/main/java/br/leno/radarcorrida/core/` (Java puro, com testes em
`app/src/test/`). Partes Android: `RadarService`, `OverlayCard`, `MainActivity`, `RideLog`.

## Instalar no celular (link único, toque e instala)

Depois da configuração única abaixo, qualquer pessoa instala assim: abre
`https://SEU-USUARIO.github.io/radar-corrida/` no celular → **Baixar e instalar** → Abrir →
Instalar. A cada mudança no código, o GitHub gera o APK novo e atualiza a página sozinho.

### Configuração única (≈ 10 min, uma vez só)
1. Crie um repositório **público** chamado `radar-corrida` no GitHub e suba esta pasta.
   Público é necessário para o GitHub Pages gratuito. O código fica visível, mas a chave de
   assinatura não: ela vai em Secrets, nunca no repositório.
2. *Settings → Secrets and variables → Actions*: crie `RADAR_KEYSTORE_BASE64` e
   `RADAR_KEYSTORE_PASSWORD` com o conteúdo do pacote `chave-radar-NAO-PUBLICAR`
   (instruções no LEIA-ME de lá).
3. *Settings → Pages → Build and deployment → Source*: **GitHub Actions**.
4. Aba *Actions* → "Gerar e publicar APK" → *Run workflow*. Em ~3 min a página está no ar.

### Alternativa: Android Studio
Abra a pasta no Android Studio, ligue o celular no USB com *Depuração USB* e clique em ▶ *Run*.

### E a Play Store?
É o único caminho de instalação em 1 toque e dispensa os avisos de "fonte desconhecida" e de
"configuração restrita". Custa US$ 25 (uma vez). Conta pessoal nova precisa de teste fechado com
12 testadores por 14 dias seguidos antes de publicar. Apps que usam Acessibilidade sem ser
ferramenta de acessibilidade precisam de declaração no Play Console, aviso no app com aceite
explícito (já implementado: tela "Antes de começar") e um vídeo mostrando esse fluxo.

## Leve por projeto
- Sem bibliotecas: nada de AndroidX, Kotlin, Google Play Services ou analytics.
- APK de produção encolhido pelo R8. O tamanho de cada versão aparece na página de instalação.
- Roda do Android 5.1 em diante (celulares e tablets antigos).
- Sem permissão de internet, GPS ou execução em segundo plano própria.
- O Android só entrega ao app os eventos da Uber/99/inDrive. No máximo ~3 leituras de tela por
  segundo, mesmo com o mapa animando. A etiqueta só é redesenhada quando a oferta muda.

## Ativar

1. Abra o app → toque em **Ativar no Android (Acessibilidade)** → *Apps instalados* →
   *Radar de Corrida* → ativar.
2. **Android 13 ou mais novo:** por ter sido instalado fora da Play Store, o Android bloqueia a
   opção ("configuração restrita"). Vá em *Configurações → Apps → Radar de Corrida → ⋮ (canto
   superior) → Permitir configurações restritas* e repita o passo 1.
3. Volte ao app: o status deve ficar verde ("Ativo").

## Configurar

Tudo é editável na tela do app, sem recompilar.

**Carro (vale para todos os perfis)**
- **Combustível (R$/l)** e **consumo real (km/l)**: custo variável por km.
- **Custo extra por km**: manutenção, pneus, depreciação, seguro rateado. Sem ele, o "lucro"
  é só sobre combustível e fica otimista.
- A tela mostra o custo/km resultante e quanto a corrida precisa pagar para atingir o lucro % do perfil.

**Perfis de motorista** (crie quantos quiser; o ativo é o que vale na rua)

| Campo | O que faz |
|---|---|
| Regra | *Todas* as metas ligadas (tempo integral) ou *qualquer uma* (renda extra) |
| Mínimo R$/km | valor ÷ km total |
| Mínimo R$/hora | valor ÷ tempo total |
| Lucro mínimo % | (valor − custo/km × km) ÷ valor |
| Bairros do trajeto | destino contendo um deles conta como meta batida (ignora acento e maiúscula) |
| Busca máxima km/min | filtro eliminatório: passou, recusa |
| Somar a busca | inclui o deslocamento até o passageiro no km e no tempo (recomendado) |

Modelos que vêm prontos (pontos de partida, calibre com o `corridas.csv`):
- **Renda extra · trajeto**: aceita se estiver no trajeto **ou** lucro ≥ 90%; busca ≤ 3 km e ≤ 8 min.
- **Tempo integral**: R$/km ≥ 1,50 **e** R$/h ≥ 40; busca ≤ 5 km.

A etiqueta mostra o veredito, "NO TRAJETO" quando aplicável, e uma linha de motivos
(✔ batida, ≈ perto, ✘ não batida) para você entender cada decisão.

**Várias ofertas na tela** (lista do inDrive, ofertas empilhadas): a etiqueta vira
"3 OFERTAS · MELHOR: R$ 30,00", com uma linha por oferta da melhor para a pior. O valor em R$
de cada linha é o que você procura na tela para tocar na certa. Ordem: veredito do perfil →
nota → lucro por hora.

## Protocolo de teste de campo (semana 1)

1. Deixe o **modo diagnóstico** ligado (vem ligado). Ele grava em `telas.txt` os textos reais
   das telas da Uber/99/inDrive. É isso que permite calibrar o leitor ao formato exato de cada app.
2. No primeiro dia, ligue também o **modo descoberta** por algumas horas: ele anota em
   `descoberta.txt` só o *nome do pacote* de qualquer app que mostre "R$" e "km". Se a 99 ou o
   inDrive tiverem um pacote diferente do padrão, ele aparece ali; corrija a lista em
   *Apps monitorados*. Depois desligue.
3. Rode normalmente. **Não mexa no app dirigindo**: ele trabalha sozinho.
4. No fim do dia: *Compartilhar telas.txt* e *Compartilhar corridas.csv* (WhatsApp/e-mail para
   você mesmo) e traga para a próxima sessão de calibração.
5. Para conferir um caso específico sem estar rodando, cole o bloco da tela no **Simulador**.

Critério de saída da Fase 0: ≥ 95% das ofertas reais lidas corretamente nas três plataformas,
conferidas contra o `telas.txt`.

## Limites e riscos conhecidos

- **Formato das telas**: o leitor foi escrito para os formatos mais comuns
  ("5 min (1,8 km)", "13,3 km · 23 min") e precisa ser calibrado com dados reais.
  Se a Uber mudar o layout, a leitura pode falhar até ajustarmos as regras.
- **Termos de uso**: as plataformas proíbem apps que interferem na operação, principalmente
  aceitar ou recusar sozinho. Este app só lê e mostra. Ainda assim, o risco de a plataforma
  detectar serviços de acessibilidade ativos não é zero; teste primeiro na sua conta sabendo disso.
- **Privacidade**: o serviço só processa eventos dos pacotes da lista (e, no modo descoberta,
  apenas o nome do pacote dos demais). Os registros ficam em
  `Android/data/br.leno.radarcorrida/files/`.
- **iPhone**: não é possível; o iOS não permite que um app leia a tela de outro.

## Próximas fases (ideias)

- Fase 1: detectar se a corrida foi aceita e concluída, para fechar ganho real por hora e km.
- Fase 2: plano de metas diárias, resumo do turno, custo fixo rateado por hora.
- Fase 3: validar com 5–10 outros motoristas antes de qualquer investimento em mídia paga.
