# Introduction

> Source: https://dev-docs.marketplace.cake.com/clockify/learn/introduction

# Introduction[#](#introduction)

## Clockify Add-on basics[#](#clockify-add-on-basics)

### What is the structure of an add-on?[#](#what-is-the-structure-of-an-add-on)

Each add-on has three main elements:

-   Manifest
-   Business logic
-   UI (optional)

_**[Manifest](/clockify/build/manifest/index)**_ describes the add-on’s capabilities and the way it integrates with the Clockify app.

_**Business logic**_ represents the functionalities provided by an add-on.

_**[UI](/clockify/build/manifest/components)**_ is the visual representation of an add-on that is displayed to users. Add-ons that don’t contain a UI can also be developed.

### How does add-on hosting infrastructure work?[#](#how-does-add-on-hosting-infrastructure-work)

Add-on resources are not hosted by CAKE.com. You must host all the resources needed for an add-on to function, including a manifest file, a database, a web server to handle communication with Clockify and any other integral part of an add-on e.g. UI.

You need to make sure that all the resources mentioned above are working and accessible.

### How does the add-on interact with the Clockify API?[#](#how-does-the-add-on-interact-with-the-clockify-api)

An add-on interacts with [Clockify's API](https://docs.clockify.me/) by supplying an [authentication token](/clockify/build/authentication-and-authorization) as part of the `X-Addon-Token` header. This authentication token will be commonly called the `add-on token` throughout the documentation.

There are several ways this token can be retrieved, as well as several [types of tokens](/clockify/build/authentication-and-authorization#tokens) that are available. The two primary ways an add-on token can be retrieved are:

-   during installation as part of the `installed` [lifecycle](/clockify/build/manifest/lifecycle)
-   when a [UI component](/clockify/build/manifest/components#interacting-with-the-api) is loaded

### How does the add-on UI integrate with Clockify?[#](#how-does-the-add-on-ui-integrate-with-clockify)

An add-on can define its UI elements in the manifest by defining [UI components](/clockify/build/manifest/components).

UI components are entry points to the UI of the add-on. They are HTML pages which Clockify loads inside [iframes](https://developer.mozilla.org/en-US/docs/Web/HTML/Element/iframe) in order to integrate them into Clockify's UI. There are [several types](/clockify/build/manifest/components#types) of UI components, each with its own locations, that can be configured.

### How does the add-on UI interact with Clockify?[#](#how-does-the-add-on-ui-interact-with-clockify)

UI components can interact with Clockify in several ways:

-   by calling the [Clockify API](https://docs.clockify.me/)

-   by calling the add-on backend, which in turn interacts with the Clockify API

-   by listening to or dispatching [window events](/clockify/build/window-events)


UI components are loaded and rendered inside iframes. At the time they are loaded, the components are provided with an [authentication token](/clockify/build/authentication-and-authorization#user-token) that they can use in order to communicate with the Clockify API. This authentication token will also contain a [set of claims](/clockify/build/authentication-and-authorization#claims) that can be used to retrieve information regarding the environment, the workspace and the user that is currently viewing the UI.

### How do add-on settings work?[#](#how-do-add-on-settings-work)

There are two ways an add-on can display an interface for its settings:

-   _**Using configurable no-code UI**_

Add-on settings can be defined in the [manifest](/clockify/build/manifest/index) with Clockify taking care of both rendering them to the user and storing the data. This approach is the fastest way to get started with building add-ons and supports building customizable settings screens in a straightforward way. Visit the [structured settings](/clockify/build/manifest/structured-settings) section for more information.

-   _**Using a custom settings UI**_

An add-on can be configured to define and host its own settings screen. This setup can be beneficial if the UI is complex, if you’d like to store settings in your own infrastructure, or if the settings need to follow a specific design. The settings UI will work the same as any other [UI component](/clockify/build/manifest/components).

### How does an add-on work?[#](#how-does-an-add-on-work)

After an add-on is installed, it's added to the workspace and loaded whenever a user loads the Clockify app.

There are several ways in which Clockify interacts with the add-on:

-   _**Lifecycle events**_: Add-on receives events when installed, deleted, if its settings are updated, or status is changed
-   _**Webhooks**_: Add-on receives webhooks for all the events it has subscribed to on the manifest
-   _**Components**_: Add-on receives requests to render a component whenever a user navigates to it
-   _**Components Window Messages**_: Add-on components can receive [window events](/clockify/build/window-events) after they are loaded

An add-on can work in both interactive (responding to user interactions or events) and non-interactive (responding to Clockify webhooks or processing server side jobs) ways.

### Can new features be added after an add-on is published?[#](#can-new-features-be-added-after-an-add-on-is-published)

You can add new features or improve existing ones after an add-on is published.

However, there are certain changes that require updating the manifest and/or other data such as the add-on name and the [marketplace](https://marketplace.cake.com/) listing that are required to go through an approval process.

Changes to the manifest, such as adding or updating components, lifecycle webhooks or scopes, will only take effect after a new version of the add-on is approved and published.

## Developer Resources[#](#developer-resources)

_**Add-on code examples**_

[Add-on code examples](https://github.com/clockify/addon-examples) are used to demonstrate how to use add-on’s specific features or functionality. These examples are tested and functional, therefore you can use them as a reference and build upon them to create your own custom integrations.

_**Add-on SDK**_

[Add-on SDK](https://github.com/clockify/addon-java-sdk) is written in Java and aims to help you with the development of your add-ons. It contains various modules to help you with the development, including schema models, validators, helpers, as well as support for web frameworks.

_**Add-on web components**_

Add-on web components are a set of components and CSS styles aimed to help you develop your UIs, and, at the same time maintain a design style that is consistent with the [CAKE.com style guide](https://www.figma.com/@cake_dot_com). For more information, visit the Add-on web components [documentation](https://resources.developer.clockify.me/ui/latest/showcase/?path=/story/introduction--page).

## Next steps[#](#next-steps)

For further information on how add-ons work in practice and how to develop an add-on you can read our [Quick Start Guide](/clockify/learn/quick-start).

[Next PageQuick Start](/clockify/learn/quick-start)

ON THIS PAGE

-   [Clockify Add-on basics](#clockify-add-on-basics "Clockify Add-on basics")
-   [What is the structure of an add-on?](#what-is-the-structure-of-an-add-on "What is the structure of an add-on?")
-   [How does add-on hosting infrastructure work?](#how-does-add-on-hosting-infrastructure-work "How does add-on hosting infrastructure work?")
-   [How does the add-on interact with the Clockify API?](#how-does-the-add-on-interact-with-the-clockify-api "How does the add-on interact with the Clockify API?")
-   [How does the add-on UI integrate with Clockify?](#how-does-the-add-on-ui-integrate-with-clockify "How does the add-on UI integrate with Clockify?")
-   [How does the add-on UI interact with Clockify?](#how-does-the-add-on-ui-interact-with-clockify "How does the add-on UI interact with Clockify?")
-   [How do add-on settings work?](#how-do-add-on-settings-work "How do add-on settings work?")
-   [How does an add-on work?](#how-does-an-add-on-work "How does an add-on work?")
-   [Can new features be added after an add-on is published?](#can-new-features-be-added-after-an-add-on-is-published "Can new features be added after an add-on is published?")
-   [Developer Resources](#developer-resources "Developer Resources")
-   [Next steps](#next-steps "Next steps")
