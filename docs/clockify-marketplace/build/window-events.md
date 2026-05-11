# Window messages

> Source: https://dev-docs.marketplace.cake.com/clockify/build/window-events

# Window messages[#](#window-messages)

Clockify uses the [window message API](https://developer.mozilla.org/en-US/docs/Web/API/Window/postMessage) in order to allow add-on developers receive messages about specific events and react accordingly.

Clockify supports two-way event communications, where the add-on can subscribe to specific events as well as dispatch events that should trigger actions on the Clockify site.

### Event subscription[#](#event-subscription)

Below is a sample snippet showing how to register a listener for an event:

```js
1handleWindowMessage = (message) => {
2  console.log(message.data.title)
3}
4
5window.addEventListener("message", (event) => handleWindowMessage(event))
```

_**Events**_

Events will contain the following fields:

`message.data.title message.data.body`

The title field will be the name of the event. The body field will be an optional payload which depends on the event type.

Current events that can be listened for are:

-   URL\_CHANGED

`message.data.body={the URL}`

-   TIME\_ENTRY\_STARTED
-   TIME\_ENTRY\_CREATED
-   TIME\_ENTRY\_STOPPED
-   TIME\_ENTRY\_DELETED
-   TIME\_ENTRY\_UPDATED
-   TIME\_TRACKING\_SETTINGS\_UPDATED
-   WORKSPACE\_SETTINGS\_UPDATED
-   PROFILE\_UPDATED
-   USER\_SETTINGS\_UPDATED

> The above events are not final and are subject to change in the future.

### Event dispatch[#](#event-dispatch)

In addition to listening for events that Clockify dispatches, the add-on can also interact with Clockify by triggering its own events.

Current events that can be dispatched from the add-on are:

-   refreshAddonToken - asks Clockify to refresh the add-on token for the user that is currently viewing the UI component. Learn more about tokens and their contexts on the [Authentication & Authorization](/clockify/build/authentication-and-authorization) section.
-   preview - if dispatched from an add-on, it will ask Clockify to open a modal with the add-on's marketplace listing.
-   navigate - asks Clockify to navigate to the location specified by the `type` parameter. It requires the following payload:

```json
1{
2  "type": "tracker"
3}
```

The following is a list of supported navigation locations:

`- tracker`

-   toastrPop - asks Clockify to show custom toast messages on the UI. It requires the following payload:

```json
1{
2  "type": "info" | "warning" | "success" | "error",
3  "message": "your message"
4}
```

Toast messages will be shown on the bottom-right section of the screen. The color of the background depends on the message type.

The following screenshot displays how an `error` toast would look like in the UI:

![Toast Message Example](/static/image/window-toast-message.a0156ecf.png)

_**Javascript Example**_

The following code example asks Clockify to display an error toast message like in the screenshot above.

```javascript
1window.top?.postMessage(JSON.stringify({ action: "toastrPop", payload: { type: "error", message: "Your add-on toast message" } }), "*");
```

[Previous PageEnvironment and Regions](/clockify/build/environments-and-regions)

[Next PageDevelopment Checklist](/clockify/publish/development-checklist)

ON THIS PAGE

-   [Event subscription](#event-subscription "Event subscription")
-   [Event dispatch](#event-dispatch "Event dispatch")
