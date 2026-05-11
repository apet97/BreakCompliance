# Lifecycle

> Source: https://dev-docs.marketplace.cake.com/clockify/build/manifest/lifecycle

# Lifecycle[#](#lifecycle)

## Definition[#](#definition)

The lifecycle of an add-on consists of all the steps beginning from when it's installed until when the add-on gets uninstalled.

Throughout its lifecycle whe add-on may be in one of the two states:

-   active - it's loaded on the Clockify UI, receives events and can interact with the Clockify API
-   inactive - it's not loaded and cannot interact with Clockify, but it's still installed and all the user data are still kept

A general lifecycle of an add-on includes the following events:

-   Installed
-   Status changed
-   Settings updated
-   Deleted

## Types[#](#types)

### Installed[#](#installed)

Add-on is installed on a workspace.

To receive this event, the add-on must declare the `INSTALLED` lifecycle hook as part of its lifecycles. During installation, a lifecycle event is triggered and the add-on is provided with the installation context and a set of [tokens](/clockify/build/authentication-and-authorization#installation-token).

> It is important to note that this payload will only be supplied **once** for each add-on installation.

We recommend persisting the installation payload in your database if your add-on meets, or plans to meet in the future, the use cases where an [installation token](/clockify/build/authentication-and-authorization#installation-token) may be needed.

The data included in the installation payload, including the API URLs and tokens, are dependent on the environment and the region where the add-on is installed. If you intend to interact with the Clockify APIs, you **must** always use the [appropriate URLs](/clockify/build/authentication-and-authorization#claims) for the specific environment.

Example of a payload that is sent as part of the `INSTALLED` event:

```http
1Request Headers
2Content-Type : application/json
3X-Addon-Lifecycle-Token : {{token}}
4
5{
6  "addonId": "62ddf9b201f42e74228efa3c",
7  "authToken": "{{token}}",
8  "workspaceId": "60332d61ff30282b1f23e624",
9  "asUser": "60348d63df70d82b7183e635",
10  "apiUrl": "{{apiUrl}}",
11  "addonUserId": "1a2b3c4d5e6f7g8h9i0j1k2l",
12  "webhooks": [{
13     "path": "https://example.com/webhook"
14     "webhookType": "ADDON"
15     "authToken": "{{token}}"
16  }], 
17}
```

The `authToken` is an API token that can be used to make authenticated requests to the Clockify API. For more information, read the [Authentication and authorization](/clockify/build/authentication-and-authorization#installation-token) sectuib.

### Status changed[#](#status-changed)

After installation, the add-on is automatically enabled and becomes active.

There are cases where the user may choose to deactivate an add-on instead of uninstalling it, for instance if they do not wish to use the add-on at the present but still want to preserve their settings and configurations.

To receive this event, the add-on must declare the `STATUS_CHANGED` lifecycle hook as part of its lifecycles.

Example of a payload that is sent as part of the `STATUS_CHANGED` event:

```http
1Request Headers
2Content-Type : application/json
3X-Addon-Lifecycle-Token : {{token}}
4
5{
6  "addonId": "62ddf9b201f42e74228efa3c",
7  "workspaceId": "60332d61ff30282b1f23e624",
8  "status": "INACTIVE"
9}
```

> The `status` values can be either `ACTIVE` or `INACTIVE`

### Settings updated[#](#settings-updated)

An add-on can have its [settings structure](/clockify/build/manifest/structured-settings) defined inside the manifest. In these cases, the add-on can subscribe to the `SETTINGS_UPDATED` lifecycle hook to be notified anytime one of its users updates the settings for the add-on.

Example of a payload that is sent as part of the `SETTINGS_UPDATED` event:

```http
1Request Headers
2Content-Type : application/json
3X-Addon-Lifecycle-Token : {{token}}
4
5{
6  "workspaceId": "60332d61ff30282b1f23e624",
7  "addonId": "62ddf9b201f42e74228efa3c",
8  "settings": [
9    {
10      "id": "txt-setting",
11      "name": "Txt setting",
12      "value": "Some text"
13    },
14    {
15      "id": "link-setting",
16      "name": "Link setting",
17      "value": "https://clockify.me"
18    },
19    {
20      "id": "number-setting",
21      "name": "Number setting",
22      "value": 5
23    },
24    {
25      "id": "checkbox-setting",
26      "name": "Checkbox setting",
27      "value": true
28    },
29    {
30      "id": "dropdown-single-setting",
31      "name": "Dropdown single setting",
32      "value": "option 1"
33    },
34    {
35      "id": "dropdown-multiple-setting",
36      "name": "Dropdown multiple setting",
37      "value": [
38        "option 1",
39        "option 2"
40      ]
41    }
42  ]
43}
```

### Deleted[#](#deleted)

When an add-on is deleted from a workspace, a lifecycle event is triggered and the add-on is provided with the context for the installation that is being uninstalled. All the [tokens](/clockify/build/authentication-and-authorization) that are provided to the add-on become invalid and from that moment on, the add-on can no longer interact with the Clockify API on behalf of the workspace user.

Example of a payload that is sent as part of the `DELETED` event:

`DELETED`

```http
1Request Headers
2Content-Type : application/json
3X-Addon-Lifecycle-Token : {{token}}
4
5{
6  "addonId": "62ddf9b201f42e74228efa3c",
7  "workspaceId": "60332d61ff30282b1f23e624",
8  "asUser": "60348d63df70d82b7183e635"
9}
```

[Previous PageManifest](/clockify/build/manifest)

[Next PageUI Components](/clockify/build/manifest/components)

ON THIS PAGE

-   [Definition](#definition "Definition")
-   [Types](#types "Types")
-   [Installed](#installed "Installed")
-   [Status changed](#status-changed "Status changed")
-   [Settings updated](#settings-updated "Settings updated")
-   [Deleted](#deleted "Deleted")
