# Private Add-ons Documentation

> Source: https://dev-docs.marketplace.cake.com/clockify/publish/private-addon-deployment

# Private Add-ons Documentation[#](#private-add-ons-documentation)

## Introduction to Private Add-ons[#](#introduction-to-private-add-ons)

Private add-ons allow developers to create solutions accessible only to specific workspaces, ideal for internal testing and use. This feature encourages users with custom integrations to transition to managed add-ons, enhancing security.

## Creating a Private Add-on[#](#creating-a-private-add-on)

### Steps to Create:[#](#steps-to-create)

1.  **Set Visibility**: Set Visibility: During creation, select "Private" visibility.
2.  **Whitelist Workspaces**: Define up to three workspaces by their IDs for access.
3.  **Manifest key**: If you already have the add-on in production, the manifest key needs to be different since the private add-on is technically a new add-on.

> **Note**: You can find your workspace ID by going to the workspace settings.

## Managing a Private Add-on[#](#managing-a-private-add-on)

### Updating Versions[#](#updating-versions)

-   Maintain whitelists across versions unless changes are needed.
-   Upon removing a workspace from whitelists, the add-on is automatically uninstalled.
-   When a new workspace is added, an email is sent notifying the user about the add-on.

## Publishing a Private Add-on[#](#publishing-a-private-add-on)

### Key Differences:[#](#key-differences)

-   **No Payment Setup**: Skip payment configuration.
-   **No Vendor Profile**: Not required for private distribution.
-   **No Review Process**: Immediate publication post-submission.
-   **Can delete a Private Add-on without waiting.**

### Deleting a Private Add-on:[#](#deleting-a-private-add-on)

-   Deletion is immediate. All installations are removed upon deletion.

## Installing a Private Add-on[#](#installing-a-private-add-on)

### Admin Instructions:[#](#admin-instructions)

1.  **Receive URL**: Obtain installation link via email notification.
2.  **Install**: Use provided URL to install the add-on.
3.  **Visibility**: See "Private" status in Clockify, distinguishing from public versions.

[Previous PagePublishing and Guidelines](/clockify/publish/publishing-and-guidelines)

ON THIS PAGE

-   [Introduction to Private Add-ons](#introduction-to-private-add-ons "Introduction to Private Add-ons")
-   [Creating a Private Add-on](#creating-a-private-add-on "Creating a Private Add-on")
-   [Steps to Create:](#steps-to-create "Steps to Create:")
-   [Managing a Private Add-on](#managing-a-private-add-on "Managing a Private Add-on")
-   [Updating Versions](#updating-versions "Updating Versions")
-   [Publishing a Private Add-on](#publishing-a-private-add-on "Publishing a Private Add-on")
-   [Key Differences:](#key-differences "Key Differences:")
-   [Deleting a Private Add-on:](#deleting-a-private-add-on "Deleting a Private Add-on:")
-   [Installing a Private Add-on](#installing-a-private-add-on "Installing a Private Add-on")
-   [Admin Instructions:](#admin-instructions "Admin Instructions:")
