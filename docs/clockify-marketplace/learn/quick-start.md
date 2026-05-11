# Clockify Add-on Quick Start Guide

> Source: https://dev-docs.marketplace.cake.com/clockify/learn/quick-start

# Clockify Add-on Quick Start Guide[#](#clockify-add-on-quick-start-guide)

## Developer account[#](#developer-account)

Before starting our development process, we will need to create a CAKE.com developer account. A developer account will allow us to publish, manage and monetize our add-ons as well as provide us with a testing environment and workspace where the add-on can be installed and tested during development. You can learn more about how to get started with the developer account [here](/clockify/build/developer-account).

## Let’s get started with developing a simple add-on.[#](#lets-get-started-with-developing-a-simple-add-on)

In this guide we’ll build a new add-on from scratch and go through the development steps one by one, but first we have to define what an add-on is.

Add-ons are software components that can be used to extend the functionalities of CAKE.com products. They are technology-agnostic, meaning they can be developed using the languages and frameworks of your choice. For this guide we will be writing our add-on in Java, using the CAKE.com [Addon SDK](https://github.com/clockify/addon-java-sdk) and [UI Kit](https://resources.developer.clockify.me/ui/latest/showcase/?path=/story/introduction--page).

## Defining our add-on and its scope[#](#defining-our-add-on-and-its-scope)

Before starting our development process, we’ll need to define how our add-on will look like and what it aims to achieve.

For this guide, we will create a simple add-on which will add a new page to the Clockify's sidebar and will display a statistic of the time tracked by the current user. As part of the guide we will implement and host a backend service and also integrate our add-on into Clockify’s UI.

## What to build[#](#what-to-build)

If you’re not sure what to build and what needs users have, you can check our [roadmap](https://clockify.me/roadmap) and get ideas from suggested add-ons or feedback [Forum](https://forum.clockify.me/) categories.

## Setting up a new project[#](#setting-up-a-new-project)

We’ll start our development flow by creating a new project for our add-on. Let’s call it “Time Reports”.

1.  We’ll create our first file named TimeReportsAddon.java, containing the following code:

```java
1package com.cake.marketplace.examples.timereports;
2
3public class TimeReportsAddon {
4    public static void main(String[] args) {
5    }
6}
```

2.  We’ll need to set up our Maven dependencies. This step consists of three parts:

-   Configure and authenticate Github with maven as described in the following [page](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-apache-maven-registry#authenticating-with-a-personal-access-token).
-   Configure our package repository in the _**pom.xml**_ file by adding the following snippet:

`<repositories> <repository> <id>github</id> <url>https://maven.pkg.github.com/clockify/addon-java-sdk</url> </repository> </repositories>`

-   Configure our package dependency in the _**pom.xml**_ file by adding the following snippet:

`<dependencies> <dependency> <groupId>com.cake.clockify</groupId> <artifactId>addon-sdk</artifactId> <version>1.4.0</version> </dependency> </dependencies>`

## Building our manifest[#](#building-our-manifest)

Now that we have successfully set up our project and dependencies we can start working on the add-on itself. First, let's briefly explain what a manifest is.

A manifest is a file which describes an add-on and its functionalities. Through the manifest you can define how your add-on integrates with CAKE.com products.

The format of the manifest file depends on the CAKE.com product your add-on is targeting, but in general will contain information about the add-on such as its identifier, name, permission scopes and other definitions that describe how it will interact with the product. You can read more about the manifest, its syntax and various options on the [manifest](/clockify/build/manifest/index) section of the documentation.

The Addon SDK provides a simple way to build and host our manifest file dynamically. Let’s start by defining the manifest object, and then we’ll go over the details.

Our _main()_ method will look like this:

```java
1public static void main(String[] args) throws Exception {
2    var manifest = ClockifyManifest.v1_3Builder()
3        .key("time-reports-example")
4        .name("Time Reports")
5        .baseUrl("")
6        .requireFreePlan()
7        .description("Example add-on that displays time statistics")
8        .scopes(List.of(ClockifyScope.TIME_ENTRY_READ))
9        .build();
10}
```

Let’s go over the lines step by step.

-   Builder interface

We'll start by using the builder interface to guide us through the steps needed to construct our manifest. The manifest builder interface exposes one method for each supported schema version. In this guide we will use version 1.3 of the manifest schema.

-   Key

The add-on key acts as an identifier for our add-on and must be a unique value among all add-ons published on the CAKE.com marketplace.

-   Name

The add-on name is also a required field and must match the name under which the add-on gets published to the CAKE.com marketplace. In the UI, the name will be displayed on Clockify's add-on settings tab.

-   Base URL

The base URL is the URL that will act as the base location for our add-on. Other paths will be defined relative to this URL. We're setting it to an empty value at this point as we do not yet have a publicly available URL we can use.

-   Subscription plan

The minimal subscription plan is the minimum plan that Clockify workspaces must have in order to be able to install and use our add-on. The `requireFreePlan()` method is a helper which sets the minimum required plan value to ‘FREE’. Other supported values for the plan can be found on the manifest section.

-   Description

The description field is an optional field, and can be populated with a short description of the add-on. The text will be visible on Clockify's add-on settings tab.

-   Scopes

Scopes are optional, but have to be defined if we intend to use certain features of the Clockify API. For our example we need to request the `TIME_ENTRY_READ` scope since we will be making requests to the time entries endpoint.

## Building our UI component[#](#building-our-ui-component)

Now that we've built the manifest object, the next step will be to define and serve our UI component. Let's start by defining our component first, and then we'll get to the HTML part of the UI.

```java
1var uiComponent = ClockifyComponent.builder()
2    .sidebar()
3    .allowEveryone()
4    .path("/sidebar-component")
5    .label("Time Reports")
6    .build();
```

In this guide we will be defining a single UI component which will be shown as a separate entry on the sidebar.

-   Builder

Similar to the manifest, we will use a builder interface to construct our component.

-   Location

Specifies where the entry point where our component will be located. We’ve chosen the sidebar location for this example, but there is a wide range of options that can be chosen depending on your use case. For more information on the location field, visit the manifest’s components section.

-   Access

Specifies the type of permission the user must have in order to access our component. Supported values are admins, or everyone. We will be showing our add-on to every user of the workspace. For more information on the access field, visit the manifest’s components section.

-   Path

The path is a value relative to the base URL defined in the manifest. A `GET` request will be made to this location every time the UI component is loaded in UI.

UI components are loaded inside iframes and will always be supplied with an authentication token query parameter, named as `auth_token`. This token can be used to identify and authenticate the user who is viewing our component. For more information on the component’s authentication, visit the [authentication](/clockify/build/authentication-and-authorization) section.

-   Label

The label is a text field whose value will be shown on the component’s defined location - the sidebar in our case. You can read more about the label under the manifest’s components section.

## Building our UI[#](#building-our-ui)

To build our UI we will use the CAKE.com [Add-on UI kit](https://resources.developer.clockify.me/ui/latest/showcase/?path=/story/introduction--page). Let’s start by creating an empty sidebar-component.html file under our resources folder.

-   Import dependencies

Let's add the required imports for the Add-on UI kit. For the purpose of this guide we will also be importing the [date-fns](https://date-fns.org/) library which will be used to process time values.

```html
1<html>
2   <head>
3       <link
4               rel="stylesheet"
5               href="https://resources.developer.clockify.me/ui/latest/css/main.min.css"
6       />
7       <script src="https://resources.developer.clockify.me/ui/latest/js/main.min.js"></script>
8       <script src="https://cdn.jsdelivr.net/npm/date-fns@4.1.0/cdn.min.js"></script>
9   </head>
10</html>
```

-   Define UI elements

Now that we’ve imported the UI kit, our next step is to create a body tag and then define our UI elements.

```html
1<body>
2    <div class="tabs">
3       <div class="tabs-header">
4           <div class="tab-header active" data-tab="tab1">Time Reports</div>
5       </div>
6       <div class="tabs-content">
7           <div class="tab-content tab1 active">
8                <p>Your total tracked time is: <span id="tracked-time"></span></p>
9           </div>
10       </div>
11    </div>
12</body>
```

-   Retrieving and decoding the authentication token

Our next goal will be to retrieve our time entries data through Clockify’s API and present a simple overview of the total time tracked. The first thing that we need to do is to create a script section at the end of our file.

```html
1<script>
2    
3</script>
```

Next, we’ll retrieve the authentication token that Clockify provides to our [component](/clockify/build/authentication-and-authorization) and use that token to make a call to the [Clockify API](https://docs.developer.clockify.me/).

```javascript
1const token = new URLSearchParams(window.location.search).get('auth_token');
```

Clockify’s add-ons can be installed on a wide range of environments, be it on a regional instance or even on development test workspaces.

To ensure that add-ons can work independent of the environment where they are installed, we have to retrieve all the environment-related information from the authentication token - which is in fact a [JWT](https://jwt.io/). You can learn more about the claims present in the JWT token on the following [link](/clockify/learn/introduction#how-does-clockify-interact-with-add-on-through-ui).

For our component, we’re mostly interested in the following three claims:

-   backendUrl - the URL of the backend (environment) where our API calls will be made
-   workspaceId - the ID of the workspace where our add-on was installed
-   user - the ID of the user that is currently viewing our component

> It should be noted that each authentication token that is supplied to the iframe only references a specific add-on install on a single workspace.

> This example will bypass the verification step and only retrieve the claims from the token, but it’s _**strongly**_ recommended that production add-ons perform the proper validations before accepting the token as valid.

> The JWT is signed with RSA256. The public key used to verify the token can be accessed on the following [link](/clockify/build/authentication-and-authorization).

```javascript
1// note: JWT tokens and their respective claims should always be verified before being accepted
2const tokenPayload = JSON.parse(atob(token.split('.')[1]));
3const backendUrl = tokenPayload['backendUrl']
4const workspaceId = tokenPayload['workspaceId']
5const userId = tokenPayload['user']
```

-   Interacting with the API

Now, it’s time to make the API call.

While Clockify provides endpoints for generating reports, to keep it simple we’re going to call the time entries endpoint which can be found [here](https://docs.developer.clockify.me/#tag/Time-entry/operation/getTimeEntries).

> Note that we're using the values we retrieved from the claims to construct the endpoint we're calling.

```javascript
1const oneWeekAgo = dateFns.subWeeks(new Date(), 1)
2
3fetch(`${backendUrl}/v1/workspaces/${workspaceId}/user/${userId}/time-entries?` + new URLSearchParams({'page-size': 500, 'start': dateFns.formatISO(oneWeekAgo),}).toString(), {
4            headers: {"x-addon-token": token}, method: "GET"
5        }
6    ).then(response => {
7        if (response.status !== 200) {
8            console.log("Received status " + response.status)
9            return
10        }
11        
12        let totalDurationSeconds = 0;
13        response.json().forEach(entry => {
14            const start = entry["timeInterval"]["start"]
15            const end = entry["timeInterval"]["end"]
16
17            const durationSeconds = dateFns.differenceInSeconds(dateFns.parseISO(end), dateFns.parseISO(start))
18            totalDurationSeconds += durationSeconds;
19        });
20
21        const element = document.getElementById("tracked-time");
22        if (totalDurationSeconds !== 0) {
23            const duration = dateFns.intervalToDuration({ start: 0, end: totalDurationSeconds * 1000 })
24            element.textContent = dateFns.formatDuration(duration);
25        } else {
26            element.textContent = "no time tracked";
27        }
28        })
29    .catch(e => {console.log("Could not retrieve time entries")
30})
```

In the above snippet, we call the Clockify API and retrieve a list of time entries for our user. Then, we iterate through the entries and sum up all the durations which will then be used to display the total duration in a human friendly way.

You will notice we supplied a header named `x-addon-token`. The token used to make this API call is the same token that was supplied at the moment our component was served.

This token has access only over the user that is currently viewing our UI component inside the Clockify iframe, and unlike the token that is supplied during the add-on install expires in 30 minutes.

## Serving our add-on[#](#serving-our-add-on)

Now that our UI is ready, we need to go back to our Java class and set up the webserver for our add-on.

The Addon SDK provides an embedded web server which we can use to quickly set up and serve our manifest and the UI.

```java
1var addon = new ClockifyAddon(manifest);
2addon.registerComponent(uiComponent, (request, response) -> {
3    var classLoader = Thread.currentThread().getContextClassLoader();
4    var inputStream = classLoader.getResourceAsStream("sidebar-component.html");
5    inputStream.transferTo(response.getOutputStream());
6    response.setStatus(HttpServletResponse.SC_OK);
7});
8
9var servlet = new AddonServlet(addon);
10var server = new EmbeddedServer(servlet);
11server.start(8080);
```

The manifest will be automatically served under the `/manifest` path, while for the UI we will register a handler which will load the HTML file from the resources and serve it to the response.

The UI component will be served under the path that was previously configured.

Our add-on is ready to be run, simply run the `main()` method and access the add-on manifest on `{baseUrl}/manifest` to install it on your development workspace.

## Testing our add-on[#](#testing-our-add-on)

### Developer account[#](#developer-account-1)

Now that we've got our add-on up and running we are ready to continue with the next step - trying out the add-on on the Clockify developer environment.

In order to do so, we first need to create a [developer account](/clockify/build/developer-account).

### Testing environment[#](#testing-environment)

Once the account is set up, the next step is to access the testing environment for our account. The testing environment is a pre-populated environment where you can test how your add-on would work in a real Clockify workspace. Testing environments are unique to each developer account, and the created workspaces have access to all Clockify features.

To access the testing environment, follow these steps:

1.  Log in to your [developer account](https://developer.marketplace.cake.com/)
2.  Navigate to the _**Test accounts**_ section and log in as one of the pre-made users
3.  You’ll be redirected to the Clockify test environment
4.  From there, go to _**Workspace settings**_
5.  Select the _**Add-ons**_ tab (visible only to users with the administrator role)
6.  Insert the link to your add-on manifest and click `Install`

A toast message will appear indicating a successful installation. All the installed add-ons will be listed on this page.

> Multiple add-ons can be installed on testing environments as long as the manifest keys are unique among them.

![Testing Environment Add-ons](/static/image/marketplace-testing-env.5ecfabd0.png)

The following info will be displayed on this page:

-   Icon (if available, if not – default image)
-   Name (from manifest)
-   Status (enabled/disabled)
-   Short description (from manifest)
-   More options (listed if there are any)
-   Settings (Optional and defined in manifest. If they exist, you can click on the Settings, and the Add-on settings page will open.)

`There are two types of settings: Clockify settings – saved on our server and specified in manifest Settings – add-on settings that can be done by developers and displayed as an iFrame on this page)`

-   Enable/disable (Installed add-on is automatically enabled)
-   Webhooks (opens a modal regarding add-on webhooks) Modal that opens displays webhooks that are sent from Clockify to this add-on.
-   Uninstall (disabled add-on and removes it from the list)

> To maintain a secure environment and prevent potential misuse, your data will be automatically cleared every month.

### Providing a public URL[#](#providing-a-public-url)

To be able to install our add-on, we first need to provide a public URL. We can do so by using a reverse proxy service such as [ngrok](https://ngrok.com/). Let's create a free account at [ngrok](https://ngrok.com/) and follow their guidelines for installing & running app [docs](https://ngrok.com/docs/getting-started/).

After you’ve setup ngrok, run the following command:

```shell
1ngrok http 8080
```

This will generate tunnel to your app running on port 8080.

![Ngrok CLI](/static/image/ngrok.46656db7.png)

Note the public URL in the output:

> [https://7ba8-188-246-34-133.eu.ngrok.io](https://7ba8-188-246-34-133.eu.ngrok.io)

We can now go back to our add-on's code and provide this public URL as the base URL for our manifest.

### Installing our add-on[#](#installing-our-add-on)

Once we've opened the add-ons tab on our workspace, we are ready to install our add-on. With the add-on webserver running and our public URL configured, we'll paste in the link to our manifest `{base URL}/manifest` and click on install.

We can now see that our add-on has been successfully installed. It will show up on the sidebar like in the screenshot below:

![Sidebar Add-on](/static/image/add-on-sidebar.5ada55ce.png)

We can now click on our sidebar entry to access our add-on's UI.

> You may notice that our sidebar entry is using a default icon. You can specify a custom icon by setting the iconPath property on the UI component.

![UI Add-on](/static/image/add-on-ui.201c349f.png)

> You may observe the UI component has been loaded inside an iframe. UI components are always loaded inside iframes, irrespective of their location.

## Next steps[#](#next-steps)

We've prepared a [Development Checklist](/clockify/publish/development-checklist) which will help you ensure that you've taken care of all the important aspects of the add-on development flow.

After you've gone through our development checklist, you are ready to check out our add-on [publishing section](/clockify/publish/publishing-and-guidelines) for a detailed overview of the add-on publishing flow.

[Previous PageIntroduction](/clockify/learn/introduction)

[Next PageManifest](/clockify/build/manifest)

ON THIS PAGE

-   [Developer account](#developer-account "Developer account")
-   [Let’s get started with developing a simple add-on.](#lets-get-started-with-developing-a-simple-add-on "Let’s get started with developing a simple add-on.")
-   [Defining our add-on and its scope](#defining-our-add-on-and-its-scope "Defining our add-on and its scope")
-   [What to build](#what-to-build "What to build")
-   [Setting up a new project](#setting-up-a-new-project "Setting up a new project")
-   [Building our manifest](#building-our-manifest "Building our manifest")
-   [Building our UI component](#building-our-ui-component "Building our UI component")
-   [Building our UI](#building-our-ui "Building our UI")
-   [Serving our add-on](#serving-our-add-on "Serving our add-on")
-   [Testing our add-on](#testing-our-add-on "Testing our add-on")
-   [Developer account](#developer-account-1 "Developer account")
-   [Testing environment](#testing-environment "Testing environment")
-   [Providing a public URL](#providing-a-public-url "Providing a public URL")
-   [Installing our add-on](#installing-our-add-on "Installing our add-on")
-   [Next steps](#next-steps "Next steps")
