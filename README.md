# Vortex – Video Player

Reprodutor Android de vídeos locais com interface premium branca, detalhes violeta/azul, marca própria Vortex e ecrã de reprodução escuro — baseado na referência visual aprovada.

## Funcionalidades implementadas no código

### Biblioteca local
- Descoberta de vídeos com MediaStore e permissões compatíveis com as versões atuais do Android.
- Abrir vídeos diretamente através do seletor de documentos do Android.
- Miniaturas com cache em memória e extração em tamanho reduzido.
- Pesquisa por nome/pasta, grelha adaptativa e filtros.
- Ordenação por data, nome, duração e tamanho.
- Agrupamento por pastas e ficha de detalhes de cada ficheiro.
- Favoritos persistidos no dispositivo.

### Reprodução
- AndroidX Media3 / ExoPlayer com sessão multimédia dedicada.
- Controlos de reprodução, seek e saltos de 10 segundos.
- Velocidade selecionável de 0.25x até 3.0x.
- Vídeo anterior/seguinte e reprodução automática opcional.
- Histórico de reprodução e retoma da última posição.
- Repetição de um vídeo e temporizador para dormir.
- Modos de imagem: ajustar, preencher/cortar e esticar.
- Alternância manual entre orientação horizontal e vertical; controlos compactos em horizontal.
- Picture-in-Picture manual.
- Seleção de faixas de áudio e legendas incorporadas.
- Importação de legendas externas SRT, WebVTT, SSA/ASS e TTML.
- Verificação de acesso/formato e estado visível do carregamento de legendas.
- Opções de áudio em segundo plano e manter ecrã ligado.

### Interface e desempenho
- Ecrã inicial Vortex com símbolo de marca em gradiente.
- Início com resumo da biblioteca, espaço ocupado, vídeo em destaque, retoma e vistos recentemente.
- Biblioteca, Pastas, Favoritos e Definições.
- Player escuro com controlos adaptados a ecrãs estreitos e modo horizontal imersivo.
- Cache de miniaturas e memorização de pesquisa/filtros/ordenação para reduzir trabalho repetido.

## Funcionalidades ainda não disponíveis
- **Chromecast de vídeos locais:** o botão de transmissão foi retirado da interface para não indicar uma função incompleta. A camada Media3 Cast permanece no projeto, mas é necessária uma ponte local segura que disponibilize o ficheiro ao recetor na rede.
- Gestos de brilho/volume e personalização fina de legendas (cor, fundo, posição e sincronização) ainda não estão implementados.

## Stack e configuração
- Kotlin 2.2.10
- Android Gradle Plugin 9.1.1
- Gradle 9.3.1
- JDK 17
- Android SDK / compileSdk 36
- Jetpack Compose + Material 3 (Compose BOM 2025.12.00)
- AndroidX Media3 1.11.1
- MediaStore e MediaSessionService

Package: `com.nexauren.vortex`

## Compilar
1. Abrir o repositório no Android Studio.
2. Usar JDK 17 e Gradle 9.3.1.
3. Instalar Android SDK Platform 36 e Build Tools 36.0.0.
4. Sincronizar o Gradle e executar a configuração `app`.

O GitHub Actions executa `assembleDebug` na branch `main`. O APK de depuração é publicado como artefacto `vortex-debug-apk` durante 14 dias após cada compilação bem-sucedida.

## Antes de uma release de produção
- Testes funcionais em dispositivos físicos e versões Android suportadas.
- Implementar e testar a ponte de rede local para Chromecast.
- Testar PiP, reprodução prolongada em segundo plano e permissões parciais em Android 14+.
- Testar legendas externas com ficheiros reais e diferentes codificações.
- Validar APK/AAB de release, assinatura e requisitos da Play Store.

> A compilação automática do APK de depuração foi concluída com sucesso. Isto confirma a compilação, não substitui os testes de reprodução e interface em dispositivos físicos.
