# CHOAM Setup

1. Ensure JDK 21+ is available (set `JAVA_HOME` or configure Gradle toolchain).
2. Build:

   ```bash
   ./gradlew build
   ```

3. Create a CHOAM configuration file at `~/.choam/config.json` based on `config.json.example`.
4. Run via the CLI:

   ```bash
   ./gradlew run --args="sync media desktop→laptop"
   ```
