# Webhooks

> Source: https://dev-docs.marketplace.cake.com/clockify/build/manifest/webhooks

# Webhooks[#](#webhooks)

## Definition[#](#definition)

Webhooks are a way for your add-on to respond to events and triggers in real-time without the user directly interacting with the add-on UI itself. They can be used to integrate your add-on with Clockify in a seamless way.

Webhook messages are automatically sent by Clockify whenever an event that the add-on has subscribed to is triggered. Clockify provides a variety of [Webhook Event types](#types) that an add-on can subscribe to according to its needs.

## Types[#](#types)

There are different types of webhooks that your add-on can subscribe to. The webhooks that are available for your add-on depend on the specific version of the [manifest schema](/clockify/build/manifest/index#top-level-properties) that you choose.

Generally, the following webhooks are available to add-ons:

`NEW_PROJECT PROJECT_UPDATED PROJECT_DELETED NEW_TASK TASK_UPDATED TASK_DELETED NEW_CLIENT CLIENT_UPDATED CLIENT_DELETED NEW_TAG TAG_UPDATED TAG_DELETED NEW_TIMER_STARTED TIMER_STOPPED TIME_ENTRY_UPDATED TIME_ENTRY_DELETED NEW_TIME_ENTRY NEW_INVOICE INVOICE_UPDATED USER_JOINED_WORKSPACE USER_DELETED_FROM_WORKSPACE USER_DEACTIVATED_ON_WORKSPACE USER_ACTIVATED_ON_WORKSPACE USER_EMAIL_CHANGED USER_UPDATED NEW_APPROVAL_REQUEST APPROVAL_REQUEST_STATUS_UPDATED TIME_OFF_REQUESTED TIME_OFF_REQUEST_APPROVED TIME_OFF_REQUEST_REJECTED TIME_OFF_REQUEST_WITHDRAWN BALANCE_UPDATED USER_GROUP_CREATED USER_GROUP_UPDATED USER_GROUP_DELETED EXPENSE_CREATED EXPENSE_UPDATED EXPENSE_DELETED ASSIGNMENT_CREATED ASSIGNMENT_UPDATED ASSIGNMENT_DELETED ASSIGNMENT_PUBLISHED`

You can test and visualize how the webhooks work and their respective payloads by triggering and listening for the events on your [development environment](/clockify/learn/quick-start#testing-environment).

## Requests[#](#requests)

Webhook requests are `POST` requests that are sent to notify the add-on of events it has subscribed to. Each specific event will contain its specific payload as well as an accompanying [signature](#signature) that can be used to verify the request. After installing an add-on, you can view a list of all the registered webhooks by navigating to the add-ons tab and clicking on the webhooks option.

![Webhooks Dropdown](/static/image/webhooks-dropdown.c82ed612.png)

A list of all the registered webhooks along with their endpoints will be displayed.

![Webhooks List](/static/image/webhooks-list.54b91c7b.png)

You can access a webhook's logs by clicking on the webhook event. The logs will contain information such as the timestamp when the request was made, the HTTP status as well as the request and response bodies.

![Webhooks Logs](/static/image/webhooks-logs.95eff78b.png)

> Webhook logs are deleted after 7 days.

## Signature[#](#signature)

Each webhook that is dispatched by Clockify will contain a signature that can be used to verify its authenticity. A typical webhook request will contain the following request headers:

`clockify-signature - this represents the token that is signed on behalf of a single webhook type for a single add-on installation clockify-webhook-event-type - this represents the event that triggered the webhook, must be one of the webhook values above`

### Webhook token[#](#webhook-token)

The webhook token supplied as part of the `clockify-signature` headers does not expire. It contains the following claims that can be used to verify its authenticity and determine its context:

`"iss": "clockify", "sub": "{add-on key}", "type": "addon", "workspaceId": "{workspace id}", "addonId": "{add-on id}"`

-   iss - the issuer of a JWT will always be `clockify`
-   sub - the sub must be the same as the add-on key
-   type - the type of a JWT will always be `addon`
-   workspaceId - the ID where the add-on is installed and where the event was triggered
-   addonId - the ID of the add-on installation on the workspace

### Authenticity[#](#authenticity)

There are a couple of precautions that we must take to verify a webhook's authenticity and prevent request spoofing.

1.  Verify the JWT

The JWT token must be verified and the issuer and the sub claims must match the expected values for our add-on. To learn more about the tokens, visit the [Authentication & Authorization](/clockify/build/authentication-and-authorization) section.

2.  Assert the webhook type is the one you expect

You must assert that the webhook types and the payloads supplied with the request match the webhook types that you expect for each endpoint.

3.  Compare webhook tokens

When an add-on which has defined an [installed lifecycle](/clockify/build/manifest/index#lifecycles) gets installed on a workspace, an installation payload is provided along with the `installed` event. If the add-on has defined webhooks in its manifest, the payload will contain information regarding registered webhooks as well as the webhook token for each of them.

`{ ... "webhooks": [ { "authToken": "{JWT for the webhook}", "path": "{path defined in the manifest}", "webhookType": "ADDON" } ], ... }`

It is recommended that add-ons retrieve and store the `authToken` for each registered webhook, so that it can later be used to verify the authenticity of the requests.

The webhook token does not expire, and the same token registered for a particular webhook will be sent as part of the `clockify-signature` header for every webhook event of that type that is triggered on the workspace.

[Previous PageUI Components](/clockify/build/manifest/components)

[Next PageSettings](/clockify/build/manifest/structured-settings)

ON THIS PAGE

-   [Definition](#definition "Definition")
-   [Types](#types "Types")
-   [Requests](#requests "Requests")
-   [Signature](#signature "Signature")
-   [Webhook token](#webhook-token "Webhook token")
-   [Authenticity](#authenticity "Authenticity")
