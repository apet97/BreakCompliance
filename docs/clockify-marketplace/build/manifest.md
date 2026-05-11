# Manifest

> Source: https://dev-docs.marketplace.cake.com/clockify/build/manifest

# Manifest[#](#manifest)

## Definition[#](#definition)

A manifest is a file which describes an add-on and its functionalities. Through the manifest you can define how your add-on integrates with Clockify.

### Schema[#](#schema)

While there are several manifest schema versions available, you should always aim to support and use the latest version of the manifest schema. At the time of writing, the latest manifest schema version is version _**1.3**_. This section will describe version 1.3 of the manifest schema.

The manifest schema is defined in the [json schema](https://json-schema.org/) format and can be retrieved at any time from the Clockify developer API by calling the following endpoint:

`GET https://developer.clockify.me/api/addons/manifest-schema`

You can use the schema above to validate your manifest file and explore the different available options.

_**Versions**_

In specific circumstances (such as for compatibility reasons), you might want to use a previous version of the schema. To do so, you have to explicitly define the `schemaVersion` attribute on the manifest.

To access a specific version of the manifest schema, you can call the following endpoint with the version parameter:

`GET https://developer.clockify.me/api/addons/manifest-schema?version=1.3`

### Top-level properties[#](#top-level-properties)

| Property | Required | Updateable | Description |
| --- | --- | --- | --- |
| schemaVersion | no | yes | The version of the manifest schema the add-on is targeting. If left undefined, it will default to the latest version. |
| key | yes | no | The add-on key acts as an identifier for our add-on and must be a unique value among all add-ons published on the CAKE.com marketplace |
| name | yes | yes | The name of the add-on. It will be displayed on Clockify's add-on settings tab |
| baseUrl | yes | yes | The base URL is the URL that will act as the base location for our add-on. Other paths will be defined relative to this URL. The value can be updated to interact with different environments of the add-on such as local or testing deployments. |
| description | no | yes | A brief description of the add-on. The text will be visible on Clockify's add-on settings tab. |
| iconPath | no | yes | Path to an image that will act as the icon for the add-on. The path must be relative to the baseUrl defined above. |
| lifecycle | no | yes | Lifecycles are messages that are automatically sent whenever an event specific to the add-on installation is triggered. Examples include events sent when an add-on is installed or uninstalled. |
| webhooks | no | yes | Webhooks are messages that are automatically sent whenever a specific event is triggered on Clockify. You can read more about the events you can subscribe to on the webhooks section. |
| components | no | yes | Components are UI elements that integrate you add-on's UI into Clockify. |
| settings | no | yes | The settings value can either be a structured object according to the schema of the structured settings or a path value pointing to a custom settings UI. Add-on settings can be accessed through the add-on menus on the add-ons tab. |
| minimalSubscriptionPlan | yes | no | Minimal subscription plan that is required for the add-on to work. This plan is used when checking if user is able to install the add-on on their workspace. |
| scopes | no | yes | Scopes represent what the add-on is permitted to do, and the data it's allowed to access. |

## [Components](/clockify/build/manifest/components)[#](#components)

| Property | Required | Description |
| --- | --- | --- |
| type | yes | The type of the component. You can read more about the different types on the components section. |
| label | yes | Custom label for component. The value will be displayed on the UI depending on the component type. |
| path | yes | Path of the component relative to the baseUrl. |
| iconPath | no | Path of the component's icon relative to the baseUrl. |
| accessLevel | yes | The access level a users must have in order to be able to view the component. |
| width | no | The width of the component. |
| height | no | The height of the component. |

Example

`"components": [ { "type": "sidebar", "label": "${sidebarLabel}", "accessLevel": "ADMINS", "path": "${sidebarPath}", "options": { "option 1": "option 1 value", "option 2": "option 2 value" }, "iconPath": "/trt" ]`

## [Lifecycle](/clockify/build/manifest/lifecycle)[#](#lifecycle)

Lifecycles contain events add-on receives when installed, deleted, or when its settings are updated.

| Property | Required | Description |
| --- | --- | --- |
| path | yes | Path relative to the baseUrl |
| type | yes | The lifecycle event you want to subscribe to. Possible values: INSTALLED, SETTINGS_UPDATED, STATUS_CHANGED, DELETED |

Example

`"lifecycle": [ { "path": "/postinstall", "type": "INSTALLED" }, { "path": "/uninstalled", "type": "DELETED" } ]`

## [Webhooks](/clockify/build/manifest/webhooks)[#](#webhooks)

| Property | Required | Description |
| --- | --- | --- |
| path | yes | Path of the endpoint relative to the baseUrl. |
| event | yes | The even that is being subscribed to. Possible values can be found on the webhooks section. |

Example

`"webhooks": [ { "event": "NEW_TIME_ENTRY", "path": "/new-time-entry" }, { "event": "NEW_CLIENT", "path": "/new-client" } ]`

> You can have a maximum of 10 webhooks per add-on.

## Settings[#](#settings)

Add-on settings allow the add-on to present the user with a configuration screen where add-on specific settings can be configured. If defined, settings will be shown under the menu dropdown of the add-on on the add-ons tab.

### Types[#](#types)

There are two types of settings an add-on can have:

-   _**[structured settings](/clockify/build/manifest/structured-settings)**_ - you can configure the structure of the settings according to the settings [schema](#structured-settings). Clockify will take care of the persisting the settings.
-   _**custom UI**_ - you can configure a custom UI by supplying a string value as the value for the `settings` field. The value will be the path where the UI will be served. The custom settings UI will work the same way as [UI components](#components) do and will be loaded inside an iframe. If following this approach, you will be responsible for handling and storing your add-on's data.

## Subscription plan[#](#subscription-plan)

Subscription plans represent the plans a workspace can be subscribed to on Clockify and the features that are available to said workspace.

Below is the list of plans that are currently supported:

-   FREE
-   BASIC
-   STANDARD
-   PRO
-   ENTERPRISE

You can read more about the different subscription plans, and the features that are available to each plan, on the [following link](https://clockify.me/help/administration/subscription-plans).

## Scopes[#](#scopes)

The scopes represent the permissions of an add-on and constrain the APIs and data that the add-on can access when interacting with the [Clockify API](https://docs.clockify.me/). A scope consists of a resource and an action. Actions can be READ or WRITE.

> Requesting the WRITE permission for a resource does not automatically grant the add-on READ permission.

> Attempting to call endpoints without declaring the appropriate scopes on the manifest will result in the request failing with a status of HTTP 403 FORBIDDEN.

It should be noted that even if a specific feature is available on the workspace plan and the relevant scopes are requested, the feature still needs to be enabled in the workspace settings in order for the APIs for that feature to work.

Below is the list of scopes that an add-on can declare in its manifest:

-   CLIENT\_READ
-   CLIENT\_WRITE
-   PROJECT\_READ
-   PROJECT\_WRITE
-   TAG\_READ
-   TAG\_WRITE
-   TASK\_READ
-   TASK\_WRITE,
-   TIME\_ENTRY\_READ
-   TIME\_ENTRY\_WRITE
-   EXPENSE\_READ
-   EXPENSE\_WRITE
-   INVOICE\_READ
-   INVOICE\_WRITE
-   USER\_READ
-   USER\_WRITE
-   GROUP\_READ
-   GROUP\_WRITE
-   WORKSPACE\_READ
-   WORKSPACE\_WRITE
-   CUSTOM\_FIELDS\_READ
-   CUSTOM\_FIELDS\_WRITE
-   APPROVAL\_READ
-   APPROVAL\_WRITE
-   SCHEDULING\_READ
-   SCHEDULING\_WRITE
-   REPORTS\_READ
-   REPORTS\_WRITE
-   TIME\_OFF\_READ
-   TIME\_OFF\_WRITE

[Previous PageQuick Start](/clockify/learn/quick-start)

[Next PageLifecycle](/clockify/build/manifest/lifecycle)

ON THIS PAGE

-   [Definition](#definition "Definition")
-   [Schema](#schema "Schema")
-   [Top-level properties](#top-level-properties "Top-level properties")
-   [Components](#components "Components")
-   [Lifecycle](#lifecycle "Lifecycle")
-   [Webhooks](#webhooks "Webhooks")
-   [Settings](#settings "Settings")
-   [Types](#types "Types")
-   [Subscription plan](#subscription-plan "Subscription plan")
-   [Scopes](#scopes "Scopes")
