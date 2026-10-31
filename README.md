# Vortex – Video Player

Reprodutor Android de vídeos locais com identidade visual clara, acabamento premium e detalhes em roxo/azul, seguindo a referência visual aprovada.

## Estado atual

**Primeira fundação implementada no repositório.** O projeto inclui:

- Aplicação Android nativa em Kotlin.
- Interface Jetpack Compose com boas-vindas, Início, Biblioteca, Pastas, Favoritos e Definições.
- Biblioteca local baseada em MediaStore, com permissões ajustadas por versão do Android.
- Miniaturas locais dos vídeos.
- Favoritos guardados no dispositivo.
- Reprodução com AndroidX Media3 / ExoPlayer.
- MediaSessionService para manter a sessão de reprodução fora do ecrã da aplicação.
- Controlo de velocidade, avanço/retrocesso, legendas incorporadas e entrada manual em Picture-in-Picture.
- Dependência e configuração inicial de Media3 Cast no manifesto.

## Stack

- Kotlin (AGP 9 built-in Kotlin)
- Jetpack Compose + Material 3
- AndroidX Media3 1.11.1
- MediaStore
- MediaSessionService
- Google Cast através do módulo Media3 Cast

## Abrir o projeto

1. Abrir a pasta do repositório no Android Studio.
2. Usar JDK 17.
3. Sincronizar com Gradle 9.3.1 e Android Gradle Plugin 9.1.1.
4. Instalar Android SDK 37.
5. Executar em dispositivo Android ou emulador. Para testar a biblioteca, o dispositivo deve conter vídeos locais.

Package: `com.nexauren.vortex`

## Próximas etapas

1. Compilar e corrigir eventuais incompatibilidades no ambiente Android Studio.
2. Integrar o seletor Cast e a transferência real de reprodução para dispositivos compatíveis.
3. Adicionar seleção/importação de legendas externas (SRT/VTT), sincronização e personalização.
4. Completar PiP automático, histórico com posição de retoma e gestão de permissões em Android 14+.
5. Testar em dispositivos físicos, otimizar miniaturas e validar release APK/AAB.

> As imagens de referência são o alvo visual. A configuração de Cast já está preparada no projeto, mas a transmissão remota ainda requer a integração do CastPlayer e testes com um recetor compatível. Não considerar esta versão pronta para publicação.
