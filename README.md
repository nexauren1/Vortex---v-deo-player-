# Vortex – Video Player

Reprodutor Android de vídeos locais com interface premium branca, detalhes violeta/azul, marca própria Vortex e ecrã de reprodução escuro — baseado na referência visual aprovada.

## Funcionalidades implementadas

### Biblioteca local
- Descoberta de vídeos com MediaStore e permissões compatíveis com Android 13/14+.
- Abrir vídeos diretamente através do seletor de documentos do Android.
- Miniaturas, pesquisa por nome/pasta e grelha adaptativa.
- Filtros: todos, recentes, favoritos, curtos e longos.
- Ordenação: mais recentes, nome A–Z, maior duração e maior tamanho.
- Agrupamento por pastas e ficha de detalhes de cada ficheiro.
- Favoritos persistidos no dispositivo.

### Reprodução
- AndroidX Media3 / ExoPlayer com sessão multimédia dedicada.
- Controlos de reprodução, seek, saltos de 10 segundos e velocidade (0.75x–2x).
- Fila com vídeo anterior/seguinte e reprodução automática opcional.
- Histórico de reprodução e retoma da última posição.
- Repetição de um vídeo e temporizador para dormir.
- Modos de imagem: ajustar, preencher/cortar e esticar.
- Picture-in-Picture manual.
- Legendas incorporadas e seleção de faixa de áudio.
- Importação de legendas externas SRT/VTT e presets de tamanho.
- Seletor Cast e Media3 CastPlayer integrados na sessão.
- Opções de áudio em segundo plano e manter ecrã ligado.

### Interface
- Ecrã inicial Vortex com símbolo de marca em gradiente.
- Início com resumo da biblioteca, espaço ocupado, vídeo em destaque, retoma e vistos recentemente.
- Biblioteca, Pastas, Favoritos e Definições.
- Player imersivo escuro com controlos adaptados a ecrãs estreitos.

## Stack e configuração

- Kotlin 2.2.10
- Android Gradle Plugin 9.1.1
- Gradle 9.3.1
- JDK 17
- Android SDK / compileSdk 36
- Jetpack Compose + Material 3 (BOM 2026.06.00)
- AndroidX Media3 1.11.1
- MediaStore, MediaSessionService, CastPlayer

Package: `com.nexauren.vortex`

## Compilar

1. Abrir o repositório no Android Studio.
2. Usar JDK 17 e Gradle 9.3.1.
3. Instalar Android SDK Platform 36 e Build Tools 36.0.0.
4. Sincronizar o Gradle e executar a configuração `app`.

O GitHub Actions executa `assembleDebug` em cada atualização da branch `main`. Quando o workflow termina com sucesso, o APK de depuração é publicado como artefacto `vortex-debug-apk` durante 14 dias.

## Ainda necessário antes de uma release de produção

- Testes funcionais em dispositivos físicos e versões Android suportadas.
- Implementar uma origem de rede local segura para disponibilizar vídeos `content://` ao recetor Chromecast; o CastPlayer e o seletor de dispositivos estão integrados, mas a transmissão de ficheiros privados locais ainda requer essa ponte.
- Testar PiP, reprodução prolongada em segundo plano e seleção parcial de permissões em Android 14+.
- Refinar sincronização, cor, fundo e posição de legendas.
- Validar APK/AAB de release, assinatura e requisitos da Play Store.

> O projeto já tem uma base de aplicação e reprodução em desenvolvimento, mas não deve ser considerado pronto para publicação até concluir os testes acima.
