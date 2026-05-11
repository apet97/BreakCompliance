# Development Checklist

> Source: https://dev-docs.marketplace.cake.com/clockify/publish/development-checklist

# Development Checklist[#](#development-checklist)

The following is a checklist that will help you go through the most important aspects that need to be accounted for when developing an add-on.

| Checklist | Description |
| --- | --- |
| Configurations | Ensure add-on is properly configured for the production environment |
| Loading time | Ensure your UI components are loaded without unnecessary delays |
| Token verification | Ensure add-on tokens are valid before accepting them |
| Token types | Ensure that the correct token is used for each use case |
| Installation token | Ensure the installation token is handled properly |
| Webhooks verification | Ensure webhook signatures are valid |
| Lifecycle verification | Ensure lifecycle signatures are valid |
| Testing on multiple environments | Ensure that your add-on is tested on multiple workspaces and with multiple user accounts |
| Handling lifecycle events | Ensure lifecycle events are handled properly, and the information is persisted and deleted as needed |
| Handling webhooks | Ensure webhook events are handled properly and respond in a timely manner |
| User preferences and UX | Ensure the UX of the add-on matches with the user's preferences on Clockify |

## Environment[#](#environment)

### Configurations[#](#configurations)

You must ensure that the release version of your add-on is configured properly for the production environment. Things that need to be checked include but are not limited to:

-   the manifest data
-   the public base URL in case you have multiple environments for you add-on (ex: development and production)
-   external configurations like database connections and API keys for external services
-   registered UI components
-   Clockify endpoint URLs

In most cases an add-on will have multiple environments and generate its manifest dynamically, with the base URL property being specified as an external configuration property.

Clockify endpoint URLs are always dependent on the environment in which the add-on is installed. You can learn more about Clockify environments on the [environments and regions](/clockify/build/environments-and-regions) page.

> You **must** ensure your production add-on does not contain hardcoded values like URLs, IDs or tokens which can break its functionality and make it vulnerable to security attacks.

### Loading time[#](#loading-time)

To provide a seamless integration for your users, you must ensure that the UI components for your add-on are loaded in a timely manner. This includes but is not limited to:

-   only serving assets that are required for the add-on to function
-   compressing and serving media assets in sensible sizes
-   serving minified versions of static assets (JS and CSS)

## Security[#](#security)

### Token Verification[#](#token-verification)

You should verify the signatures of every token that the add-on receives before accepting it as valid. All tokens that are signed by Clockify will be JWT tokens signed with RSA256. You can read more about the verification on the [Authentication & Authorization section](/clockify/build/authentication-and-authorization#token-verification).

The following is a checklist of items that should be checked when verifying a token:

-   [token signature is verified](/clockify/build/authentication-and-authorization#token-verification)
-   token algorithm is `RS256`
-   `iss` claim is 'clockify'
-   `type` claim is 'addon'
-   `sub` claim matches with the addon manifest key
-   token is not expired

Additionally, a token might be valid, but it might not be signed on behalf of the expected workspace. For instance, a token that is signed on behalf of an add-on on workspace A might be used to gain access to resources belonging to workspace B.

You should use the following claims to verify that a request matches the specific workspace for which it was intended:

`addonId - the ID of the installation on the workspace workspaceId - the ID of the workspace`

### [Token types](/clockify/build/authentication-and-authorization#tokens)[#](#token-types)

You should ensure the correct token type is used depending on the needs. Generally you should aim to use the token with the least access, which is the user token, wherever possible.

User tokens can be used both on the backend and frontend parts of an add-on. Installation tokens should never be used on the frontend.

### [Installation token](/clockify/build/authentication-and-authorization#installation-token)[#](#installation-token)

As described on the [Authentication & Authorization section](/clockify/build/authentication-and-authorization#installation-token), the installation token is a type of token that has full access over a workspace and _**does not expire**_.

Due to the sensitive nature of its scope and the fact that it does not expire, care should be taken _**not to leak**_ the installation token to an end user, to the UI of the add-on or to a third party.

> The installation token should never be logged. If present in request headers, you should ensure that it's properly redacted.

### Webhooks verification[#](#webhooks-verification)

You should verify the signatures of every webhook event you receive before accepting it as valid. You can read more on how to verify the signature of a webhook on the [webhooks section](/clockify/build/manifest/webhooks#authenticity). It is also recommended that you validate information from the payload against the claims of the provided token.

> For example, you should ensure the workspace ID referenced in the payload of a time off request matches with the workspace ID that is part of the token claims.

### Lifecycle verification[#](#lifecycle-verification)

Similar to [webhooks](#webhooks-verification), lifecycle signatures must also be verified before being accepted as valid. You can read more about the verification on the [Authentication & Authorization section](/clockify/build/authentication-and-authorization#token-verification).

## Add-on behavior[#](#add-on-behavior)

### Testing on multiple environments[#](#testing-on-multiple-environments)

You must ensure your add-on works as expected on [different environments](/clockify/build/environments-and-regions) and workspaces. The CAKE.com [Developer Portal](https://developer.marketplace.cake.com/) provides access to pre-populated [testing environments](/clockify/learn/quick-start#testing-environment) where you can test how your add-on behaves. We recommend that you thoroughly test your add-on's behavior on multiple workspaces (by creating multiple developer accounts) and with multiple users and access levels (each testing environment allows you to log in on behalf of various pre-existing users).

> You **must** ensure that your add-on does not leak data to unauthorised workspaces or users.

### Lifecycle[#](#lifecycle)

-   install - it is recommended that you [persist the installation payload](/clockify/build/manifest/lifecycle#installed) as the `installed` event will only be dispatched once per installation
-   uninstall - it is recommended that your add-on takes care to properly handle uninstall events, including: removing database entries, cancelling scheduled jobs, invalidating tokens etc

### Webhooks[#](#webhooks)

All webhook events contain a signature under the `clockify-signature` header. This signature must match with the corresponding add-on installation. Additionally, the `clockify-webhook-event-type` header must also match with the expected webhook event.

_**Timeout**_

You should aim to respond to webhook events in a timely manner. If you must execute a long-running operation that is triggered by a webhook event, you should schedule it to be executed asynchronously and avoid blocking the request.

## Add-on UX[#](#add-on-ux)

Add-ons should support the configurations and the preferences set by the user on Clockify.

These include but are not limited to:

-   theme (light/dark)
-   language
-   date and time formats
-   timezone and location
-   currency settings

Some of the above can be retrieved from the token claims present in the [user token](/clockify/build/authentication-and-authorization#user-token).

[Claims present](/clockify/build/authentication-and-authorization#claims) in the user token are:

-   `language`
-   `theme`

Information about the other properties can be retrieved by making an [authenticated API request](/clockify/build/authentication-and-authorization) to the [Clockify API](https://docs.clockify.me/#tag/User/operation/getLoggedUser).

[Previous PageWindow Events](/clockify/build/window-events)

[Next PagePublishing and Guidelines](/clockify/publish/publishing-and-guidelines)

ON THIS PAGE

-   [Environment](#environment "Environment")
-   [Configurations](#configurations "Configurations")
-   [Loading time](#loading-time "Loading time")
-   [Security](#security "Security")
-   [Token Verification](#token-verification "Token Verification")
-   [Token types](#token-types "Token types")
-   [Installation token](#installation-token "Installation token")
-   [Webhooks verification](#webhooks-verification "Webhooks verification")
-   [Lifecycle verification](#lifecycle-verification "Lifecycle verification")
-   [Add-on behavior](#add-on-behavior "Add-on behavior")
-   [Testing on multiple environments](#testing-on-multiple-environments "Testing on multiple environments")
-   [Lifecycle](#lifecycle "Lifecycle")
-   [Webhooks](#webhooks "Webhooks")
-   [Add-on UX](#add-on-ux "Add-on UX")
