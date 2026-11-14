rootProject.name = "signal-bot"

// NOTE: `adapter-broker-private` is intentionally NOT included here.
// It is a private, git-ignored module (see .gitignore and its README stub).
include("domain", "application", "adapters")
