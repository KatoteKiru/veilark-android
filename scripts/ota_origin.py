"""Private-client download origin contract, independent of deployment credentials."""

OTA_ORIGIN = "https://nl2.senyasenyavski.uk:2096"


def validate_ota_origin(value: str) -> str:
    # Installed private clients pin both host and port for the APK, not just
    # the manifest. Catalogue/browser URLs on 443 are a separate delivery path.
    origin = value.rstrip("/")
    if origin != OTA_ORIGIN:
        raise ValueError("Private Android OTA APK URL must use the installed client's pinned origin: " + OTA_ORIGIN)
    return origin
