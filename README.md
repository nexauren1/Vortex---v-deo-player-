# Vortex – Video Player

Reprodutor Android de vídeos locais com interface clara premium, detalhes em roxo/azul e ecrã de reprodução escuro, seguindo a referência visual aprovada.

## Implementado nesta primeira etapa

- Aplicação Android nativa em Kotlin.
- Interface Jetpack Compose com boas-vindas, Início, Biblioteca, Pastas, Favoritos e Definições.
- Identidade visual branca com gradientes violeta/azul e ícone adaptativo Vortex.
- Descoberta de vídeos locais via MediaStore, com permissões para Android 13/14+ e versões anteriores.
- Miniaturas locais, pesquisa na biblioteca, agrupamento por pastas e favoritos persistidos.
- Reprodução com AndroidX Media3 / ExoPlayer.
- MediaSessionService para manter a sessão multimédia disponível fora do ecrã da aplicação.
- Controlos de reprodução, busca na timeline, velocidade, legendas incorporadas e entrada manual em Picture-in-Picture.
- Seletor de ficheiros de legendas externas SRT e WebVTT.
- Media3 CastPlayer e botão de seleção de dispositivos Cast integrados no ecrã de reprodução.
- Metadados de título/tipo de vídeo associados à sessão e ao item multimédia.
- Workflow GitHub Actions para compilar o APK de depuração.

## Stack

- Kotlin 2.4.10
- Android Gradle Plugin 9.1.0
- Jetpack Compose + Material 3
- AndroidX Media3 1.11.1
- MediaStore
- MediaSessionService
- Media3 CastPlayer

## Abrir o projeto

1. Abrir a pasta do repositório no Android Studio.
2. Usar JDK 17 e Gradle 9.3.1.
3. Instalar o Android SDK Platform 36 e Build Tools 36.0.0.
4. Sincronizar o Gradle e executar a configuração `app`.
5. Testar num dispositivo Android com vídeos locais.

Package: `com.nexauren.vortex`

## Trabalho ainda necessário antes de release

1. Concluir e validar a compilação do APK de depuração pelo workflow.
2. Implementar o servidor HTTP local/URI acessível na rede para transmitir ficheiros `content://` locais ao recetor Chromecast. O CastPlayer e o seletor de dispositivos já estão integrados, mas a transmissão de ficheiros locais ainda não deve ser considerada concluída até esta ponte estar pronta e testada numa TV/recetor real.
3. Acrescentar personalização completa de legendas (tamanho, cor, fundo, posição e sincronização).
4. Guardar histórico e posição de retoma por vídeo.
5. Testar Picture-in-Picture, reprodução prolongada em segundo plano, permissões parciais do Android 14+ e formatos em vários dispositivos.
6. Validar APK/AAB de release, ícone, tradução e comportamento em dispositivos reais.

> Esta é uma primeira implementação funcional em evolução, não uma versão final pronta para publicação na Play Store. As imagens de referência são o alvo visual; a interface e a lógica estão a ser construídas em etapas.
