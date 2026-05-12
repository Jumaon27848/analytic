# App Config Endpoints

Two read-only endpoints expose third-party SDK configuration for a given Android package. The proxy fetches the config from the main server and forwards only the fields the client needs. The response body is encrypted using the same scheme already used by the other `/users/...` endpoints.

## Endpoints

### `GET /v1/packages/{package}/appsflyer_config`

Returns the AppsFlyer SDK configuration for `{package}`.

### `GET /v1/packages/{package}/clarity_config`

Returns the Microsoft Clarity SDK configuration for `{package}`.

### Path parameters

| Name        | Type   | Description                       |
| ----------- | ------ | --------------------------------- |
| `package` | string | Android application package name. |

## Response

`200 OK` — `application/json`:

```json
{
  "payload": "<base64 string>"
}
```

`payload` is an AES-encrypted, base64-encoded string. After decryption it is a UTF-8 JSON object (see schemas below).

### Error responses

| Status  | When                                                                   |
| ------- | ---------------------------------------------------------------------- |
| `404` | The requested config is not configured for this package on the server. |
| `500` | The proxy could not reach or parse a response from the main server.    |

## Decrypting `payload`

The encryption is identical to the request encryption already used for the `POST /users/{firebase_analytic_app_id}/...` endpoints, just applied in the opposite direction.

| Parameter  | Value                                                             |
| ---------- | ----------------------------------------------------------------- |
| Algorithm  | AES-256-CBC                                                       |
| Key        | 32-byte UTF-8 key (the same shared key used for request payloads) |
| IV         | First 16 bytes of the base64-decoded `payload`                  |
| Ciphertext | Remaining bytes after the IV                                      |
| Padding    | PKCS#7 (block size 16)                                            |
| Plaintext  | UTF-8 JSON string                                                 |

Pseudocode:

```
raw        = base64_decode(payload)
iv         = raw[0..16]
ciphertext = raw[16..]
plaintext  = pkcs7_unpad(AES_CBC_decrypt(key, iv, ciphertext))
config     = json_parse(plaintext.decode("utf-8"))
```

The decoded plaintext is guaranteed to be valid JSON.

## Decrypted payload schemas

### AppsFlyer config

```json
{
  "dev_key": "string",
  "is_debug_mode": true,
  "is_logging_enabled": false,
  "web_2_app_subscription_ids": ["string", "string"]
}
```

| Field                          | Type     | Notes                                                                    |
| ------------------------------ | -------- | ------------------------------------------------------------------------ |
| `dev_key`                    | string / null   | AppsFlyer dev key to initialise the SDK with.                            |
| `is_debug_mode`              | bool     | Enable AppsFlyer debug mode.                                             |
| `is_logging_enabled`         | bool     | Enable AppsFlyer logging.                                                |
| `web_2_app_subscription_ids` | string[] | Subscription IDs used by the web-to-app subscription flow. May be empty. |

### Clarity config

```json
{
  "project_id": "string",
  "is_logging_enabled": true
}
```

| Field                  | Type   | Notes                                          |
| ---------------------- | ------ | ---------------------------------------------- |
| `project_id`         | string / null | Clarity project ID to initialise the SDK with. |
| `is_logging_enabled` | bool   | Enable Clarity SDK logging.                    |

## Example

Request:

```
GET /v1/users/com.example.app/appsflyer_config
```

Response:

```json
{
  "payload": "Qk1QSzlsK0tHaXc4Q0VnbW9KRXc4eFp1V0xGcGVHWE..."
}
```

After decryption (`payload` → AES-CBC → JSON):

```json
{
  "dev_key": "fgbghjmfdg",
  "is_debug_mode": true,
  "is_logging_enabled": false,
  "web_2_app_subscription_ids": ["askdhsajkd", "asjkdhsajd"]
}
```
