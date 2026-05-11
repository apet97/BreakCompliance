# Publishing

> Source: https://dev-docs.marketplace.cake.com/clockify/publish/publishing-and-guidelines

# Publishing[#](#publishing)

## Guidelines & best practices[#](#guidelines--best-practices)

### What’s the criteria add-ons need to meet in order to be published?[#](#whats-the-criteria-add-ons-need-to-meet-in-order-to-be-published)

We want to make sure that every individual using any CAKE.com Marketplace add-on gets a product that is useful, has high performance and uses their data responsibly. That’s why we’ve compiled a set of instructions and guidelines that can help you build the add-on that meets these standards. Prior to building, please, take a couple of minutes and read through these to make sure that the add-on you want to build complies with the requirements listed below.

### What are general guidelines for add-on content?[#](#what-are-general-guidelines-for-add-on-content)

_**Become familiar with the system.**_

Clockify is a complex system consisting of many components with all sorts of dependencies between them: Timesheet, Timetracker, Calendar, Report, Project, etc. They all work together and complement each other perfectly. That’s why, it’s important you’re familiar with all the entities and patterns that Clockify comprises, so that you can use them in a way that Clockify’s customers are familiar with.

To better understand elements used to build Clockify, check out Clockify’s [Glossary of terms](https://clockify.me/help/getting-started/clockify_basics/clockify-glossary).

_**Strive to enhance customer experience.**_

If you’re already our customer, you can start building an app that will benefit you and your team, solve your problems and later share it with everyone else. If you’ve never used our product, we recommend you create [a free account](https://app.clockify.me/en/login). Try to build something new and unique.

_**Be clear about the purpose.**_

Sharing data with a third party can be scary for some users. Therefore, you should be clear on how what you built adds value to their customer experience and state clearly what their data will (and won’t) be used for.

_**Don’t mislead your users.**_

Make sure you clearly stated add-on’s purpose and necessary details when creating your first version. You should also provide clear descriptions, screenshots and videos in order to describe your add-on accurately.

_**Explore our branding guidelines.**_

Since an add-on you’re building will be part of an existing product, we suggest you take a look at our [product branding guidelines](https://www.figma.com/@cake_dot_com) and check if the assets you built follow these guidelines before you submit an add-on for a review.

_**Make an easy setup.**_

More people are likely to use an add-on that’s easy to set up. This is one of the crucial characteristics that attracts new users. For instance, if using your product requires an account, it should be created by getting the credentials from Clockify. If you need to ask customers to sign up manually, you should pre-fill some user information, e.g. user email.

_**Inform user of a third-party account.**_

If, in order to use add-on, user needs to create a separate third-party account (i.e. log in to another service), this must be stated clearly to the user in the description and the overview of the add-on.

_**Check grammar and spelling.**_

Add-on should not contain any typos, or grammatical errors. Therefore, check grammar and spelling prior to submitting it for a review.

### Is there any type of content that is prohibited?[#](#is-there-any-type-of-content-that-is-prohibited)

To ensure that CAKE.com Marketplace is a safe platform, we’ve created standards that define and prohibit content that is harmful, inappropriate or offensive to others.

_**Hate speech.**_

Add-ons that promote violence, or incite hatred against individuals, or a group of individuals based on race, ethnicity, religion, age, disability, nationality, sexual orientation, gender, gender identity, or any other characteristic associated with discrimination will not be published.

_**Violence.**_

We don’t allow add-ons that facilitate or promote violence or endangerment of an individual’s safety.

_**Bullying and harassment.**_

Add-ons that contain or encourage threats, harassment and bullying will not be published.

_**Harmful and malicious products.**_

Add-ons that promote or support any type of harmful and malicious products won’t be published.

### Final note[#](#final-note)

Keep in mind that assets that contain any type of prohibited content will not be published. Also, any prohibited, or harmful content that is added after reviewing and publishing, and reported by users, will be removed from the marketplace immediately. Please note that these guidelines should be used in addition to our [Terms of Use](/terms.html), [Privacy Policy](/privacy.html), [Security Requirements](#security-requirements) and [Listing Agreement](/cake-com-marketplace-listing-agreement.html). Be sure to read all of these documents. Also, keep in mind that these guidelines evolve along with our business. Feel free to get in touch if you have any feedback, or suggestions.

## Create add-on[#](#create-add-on)

### How to create add-on?[#](#how-to-create-add-on)

You’ll have complete independence and autonomy over building your add-on. This means that you’ll build it locally, but before submitting your code for a review, you should test it in the testing environment provided to you. Also, check out our [REST API documentation](https://docs.developer.clockify.me/) and Development Toolkit section to get a better insight on how to build and maintain add-ons. Add-on then needs to be approved by our developers in the reviewing process and then it can be published.  
To make sure the whole process goes smoothly and your request is approved shortly, we will provide you with the necessary requirements and standards all CAKE.com add-ons should meet in order to be published.

> When creating an add-on you’re actually creating the first version of your add-on. All subsequent iterations will be defined as versions in the Marketplace (e.g. v 1.0.1).

To create add-on:

1.  Log in or sign up for your [developer account](https://developer.marketplace.cake.com/)
2.  Go to _**Add-ons**_ page
3.  Click _**CREATE ADD-ON**_

Now, complete a two-step process and enter the required data.

1.  Basic information

![Create Add-on](/static/image/create-add-on.5848b57c.png)

-   _**Add-on icon**_: Upload image
-   _**Add-on name**_: Choose a descriptive name that users can easily understand
-   _**Add-on manifest**_: Enter URL
-   _**Product**_: Refers to the application for which you are creating add-on

At the moment, you can only choose Clockify. You’ll be able to create add-ons for Pumble and Plaky soon.

-   _**Visibility**_: Public – Add-on is visible to everyone and allows users within the CAKE.com community to share it.

At the moment, add-ons can only be public. Private add-ons are coming soon.

-   _**Pricing model**_: Define pricing model for your add-on.

`Free Paid`

Click _**NEXT**_ to proceed to the second step of the form.

> You can always save the form as draft and edit it later.

2.  Listing

Information you add in the listing will be available in the Marketplace. Use it to help drive users to your add-on.

![Listing Add-on](/static/image/listing-add-on.630186ee.png)

-   _**Choose category**_: Define a niche or business sector you’d like to improve by building this add-on. In the Marketplace, published add-on will be placed in the category you selected here.
-   _**Short description**_: Give a short overview of the purpose of your add-on and solution it provides
-   _**Long description**_: Explain what your add-on is providing in greater detail and the value it brings to users
-   _**Privacy policy**_: Define privacy policy and clearly disclose which information you’re collecting in this manner
-   _**Terms of use**_ (optional): Along with privacy policy, you can also add terms of use users need to agree to, in order to proceed with installation

> Please keep in mind that, if there are some contradictory statements in terms of use you provided here, to those written by our legal team, CAKE.com’s legal policy should be followed.

-   _**Website**_ (optional): Add a link to the website that contains more information about add-on. Creating a website that promotes your add-on can help you attract more users.
-   _**Add-on gallery**_ (optional): Upload a set of images, or screenshots that reflect the purpose and promote your add-on in the best way. You can upload up to 5 images.
-   _**Demo video**_ (optional): Add demo to spread the word, boost traffic, or use it as an instructional video. Video needs to be publicly accessible on YouTube.

We encourage you to provide some additional instructions, including information that would be relevant to the person doing an add-on review like some additional notes or any other kind of specific guidelines.

Finally, by checking the Marketplace Listing Terms box you agree that you’ll meet and oblige the [listing terms](/cake-com-marketplace-listing-agreement.html) stated there as long as your add-on is part of the CAKE.com Marketplace.

Information provided in this step will be published and available along with the add-on on the [Marketplace website](https://marketplace.cake.com/).

> Information provided in the two-step forms above, must be written in English.

After you’ve completed the two steps, click the _**SUBMIT FOR REVIEW**_ button and your add-on will be submitted to the CAKE.com Marketplace team for a review.

> Once you’ve submitted your add-on for a review, our team will start the reviewing process soon as possible.

### How does reviewing work?[#](#how-does-reviewing-work)

> In order to successfully submit your add-on version for a review, all the fields need to be filled out correctly and there should be no pending requests.

Once you click the _**SUBMIT FOR REVIEW**_ button, a new add-on version request will be created. Request will get a pending status and will be sent to our team for a review. You will get an email that confirms you have successfully submitted a request for new version.

> Reviewing process will take approximately two weeks.

When it’s done, you will receive an email informing you that your request is approved, or denied. The request will also be appropriately marked as approved or denied in your profile.

If denied, we’ll send you the feedback notes from our team that could help you understand why the request was denied. You’ll also be able to see the request, edit it based on the feedback you received and submit it for a review again.

If approved, you can continue and publish the version directly from the email you received, by clicking _**PUBLISH**_. You’ll receive another confirmation email that will inform you that your add-on is published and available in the marketplace. Click _**View \[addonname\]**_ to see it in the marketplace.

## Create version & publish[#](#create-version--publish)

### How to create new version?[#](#how-to-create-new-version)

Every created add-on must have a version. Versioning add-ons helps you create and improve add-on’s functionality easily and gradually without causing inconvenience to your users.

In order to create a new version, at least one version of an add-on needs to be published. Learn more about how to create add-on’s first version on Create Add-on section above.

First version will be automatically created as version 1.0.0. For each new request, you need to manually add the version number. Version number should reflect complexity of the functionalities with major version being marked as e.g. 1.1.0 and minor version as e.g. 1.0.1. Also, keep in mind that version numbers should be marked in a logical progression, e.g. 2.0.0 version cannot be followed by version 1.5.0.

Creating new version is a three-step process similar to the one used to create an add-on. However, when creating new version, add-on name and icon in the Basic information page are generated from the published version, but can be edited. Steps two and three are the same.

### Which status can version request have?[#](#which-status-can-version-request-have)

Based on its lifecycle stage, status of add-on’s version request can be:

-   Draft

Version request is in draft if you haven’t completed all the steps when creating an add-on, but you saved information you entered by clicking the Save as draft button.

-   Pending

When you submit the version for a review, its status is updated to Pending. After review, the version can be approved or declined with status changing accordingly.

> There can be only one pending request per add-on.

-   Declined

After your version is reviewed, it can be approved or declined. If your version is declined, along with that information, you will also get an explanation on why. You can edit the declined version based on this feedback and submit it for a review again.

-   Approved

An approved version is approved by our team. It goes further into the publishing cycle and is ready to be published.

-   Published

This is the final step in the publishing cycle. Once the version is approved, it can be published and when published it becomes available in the marketplace for everyone to use. After you published one add-on version, you can proceed by creating a new one.

Once your add-on (first, or any subsequent version) has been published on the CAKE.com Marketplace, you’ll receive a confirmation email saying that your add-on version has been successfully published.

> Add-on can have only one published version.

If an add-on is already in the marketplace, once its new version request is published, the existing published version immediately becomes outdated.

![Add-on Statuses](/static/image/add-on-statuses.3fad1f11.png)

-   Outdated

If a newer version is published, the existing one becomes automatically deprecated and outdated.

-   Unlisted

This status is added to the published version that has been removed from the marketplace and can’t be used or installed by new users. However, those who use the unlisted version can still see and use it.

## Delete add-on[#](#delete-add-on)

You can delete an entire add-on with all of its versions regardless of their status, or just an add-on’s version request. You can do both of these actions from the Add-ons page, in your profile. However, deleting an entire add-on requires sending a request to our support team.

### How to delete add-on?[#](#how-to-delete-add-on)

If you’d like to delete your add-on, you can do that from the _**Add-ons**_ page.

To delete an add-on:

1.  Click _**Delete add-on**_ in the bottom right corner
2.  Delete add-on modal appears
3.  Click _**SEND REQUEST**_

![Add-on Delete](/static/image/delete-add-on.72e66493.png)

After you’ve sent the request to delete an add-on, our support team will contact you shortly, and ask for confirmation. Soon after your confirmation, the process of deletion will start. Add-on users will receive an email that informs them of deletion and you will receive an email that notifies you that the process has begun.

Once the process has started, add-on is unlisted, removed from the marketplace and unavailable for new users. The existing users will be able to use the add-on until it is deleted. To ensure that they are informed, after the deletion process is set in motion, information of the deletion and add-on’s retirement date will be prominently displayed at the top of the page throughout the application.

> The process of deletion lasts 31 days. An add-on will be deleted at the end of the last day at midnight. This process cannot be canceled or reversed.

After it is deleted:

-   Add-on will be deleted from the marketplace
-   Your profile and all users will lose access to it
-   Subscription will be canceled for paid add-ons

### How to delete add-on version request?[#](#how-to-delete-add-on-version-request)

If you don’t want to continue with the process of creating a new add-on version, you can delete it in the request preview table, in the _**Add-ons**_ page.

When deleting an add-on version, depending on the status of the version, different actions are available.

If version is in:

-   Draft

If this is the only version request for this add-on, by deleting it, you will delete an entire add-on and if not, you will delete only draft version of this add-on.

-   Pending

If this is the only version request for this add-on, by deleting it, you will delete an entire add-on and if not, you will delete only draft version of this add-on.

-   Approved

If this is the only version request for this add-on, by deleting it, you will delete an entire add-on and if not, you will delete only draft version of this add-on.

-   Declined

If this is the only version request for this add-on, by deleting it, you will delete an entire add-on and if not, you will delete only draft version of this add-on.

-   Outdated

If this is the only version request for this add-on, by deleting it, you will delete an entire add-on and if not, you will delete only draft version of this add-on.

> You cannot delete the Unlisted and Published add-on versions since that action would affect potential users of those add-ons.

## Data privacy guidelines[#](#data-privacy-guidelines)

As you’ll have access to customer information which includes personal data, it’s important how you store and handle the data. Personal data is sensitive and in many cases regulated by applicable laws. Listed below are best practices you should follow, in addition to the [Terms of Use](/terms.html), when developing the add-on on the CAKE.com marketplace.

### Clearly explain your data privacy practices[#](#clearly-explain-your-data-privacy-practices)

When submitting your add-on for review you are required to provide a Privacy policy which should explain to the users how you plan on using their data. The privacy policy should clearly explain to the user what data the add-on will collect, how that data will be used, who will have access to the data and explain the user’s choice.

### Minimize the data you collect[#](#minimize-the-data-you-collect)

Collect data only where you need it. Do not collect the data because you think it may be useful later. Where personal data is involved, consider de-identifying it. Also consider deleting user data when they request it or when they uninstall your add-on. Have in mind that you don’t need to store data indefinitely, put some data retention schemes in place.

### Get consent for certain data use[#](#get-consent-for-certain-data-use)

When submitting an add-on for review you’ll have to check which data you collect in the permissions tab. You should only collect the data you checked in that tab. In this way users consent to the usage of their data when installing an add-on, but only for the purpose of an add-on. Using data for marketing, sharing data with third-parties and other data use cases not strictly required to support the operation of your add-on may require a separate consent from the user before collecting or using the data. As a general rule of thumb, you should always get consent if the user would not expect their data to be used or shared in a particular way given the purpose of your add-on.

Consent may not be embedded in a privacy policy. Instead, it must be collected from the user directly. You are responsible for collecting and maintaining all such consents, either through the add-on itself or through direct communication with the add-on user.

Note, regardless of whether you obtain consent, some data use cases may be prohibited by the [Terms of Use](/terms.html). You are responsible for reviewing and complying with those terms.

### Provide access, modification and erasure of personal data[#](#provide-access-modification-and-erasure-of-personal-data)

Applicable laws and data management best practices require that you make it easy for users to get a copy of, correct and delete their personal data. This means, if you are storing personal data, you need to know where that personal data is at all times and be able to update it or remove it upon request.

### Offer additional data processing terms[#](#offer-additional-data-processing-terms)

If you are accessing, storing or otherwise processing personal data of EEA residents, users may request that you sign and comply with additional data protection terms, consistent with Article 28 of the General Data Protection Regulation (“GDPR”). You are responsible for understanding and complying with the terms required under Article 28 of the GDPR as it relates to the user data you access, store or otherwise process in connection with the user’s consent to install and share data with your add-on.

### Invest in data security[#](#invest-in-data-security)

You must take reasonable steps to protect user data shared with you and collected by your add-on, including user device information. We recommend you to follow our Security guidelines for a more comprehensive list for securing your add-on.

In the event your add-on or suppliers experience a data security breach, you are responsible for communicating with users and regulators, as required by applicable law. It’s also important to let us know of the incident by emailing to [support@cake.com](mailto:support@cake.com).

## Security requirements[#](#security-requirements)

### Authentication & authorization[#](#authentication--authorization)

An add-on must authenticate and authorize every request on all endpoints exposed. Anonymous access to application endpoints and resources can be allowed in scenarios where it is needed.

### Data protection[#](#data-protection)

1.  Any CAKE.com End User Data stored by an application outside of the CAKE.com product or users’ browser must ensure full disk encryption at-rest. If accessed by an application or a service, it should be authenticated and authorized appropriately.
2.  An application must use TLS version 1.2 (or higher) to encrypt all of its traffic, and enable HSTS with a minimum age of one year.
3.  An application must follow the “Principle of Least Privilege”, when requesting app scopes. This means that an application should only request scopes required to perform its intended functionality, and nothing more.
4.  An application must securely store and manage secrets, which include OAuth tokens, sharedSecret, API keys, and encryption keys. They cannot be stored in places that are easily accessible. Examples of places include:

-   Source code and code repository tools, such as Bitbucket and Github
-   URL strings
-   Referer headers
-   Application logs

### Application security[#](#application-security)

1.  An application must maintain and securely configure domains where the application is hosted.
2.  When applicable, an application must enable security headers and cookie security attributes.
3.  An application must validate and sanitize all untrusted data and treat all user input as unsafe to mitigate injection-related vulnerabilities. Untrusted data is any input that can be manipulated to contain a web attack payload.
4.  An application must not use versions of third-party libraries and dependencies with known critical or high vulnerabilities. When vulnerabilities in these libraries and dependencies are discovered, application developers must remediate them as quickly as possible.

### Privacy[#](#privacy)

1.  An application must not collect or store credentials belonging to CAKE.com user accounts such as user passwords or user API tokens.

### Vulnerability management[#](#vulnerability-management)

1.  You must notify CAKE.com of all security incidents via [support@cake.com](mailto:support@cake.com).
2.  Your account email will be taken as a security contact where you’ll be notified about vulnerabilities in the app.

[Previous PageDevelopment Checklist](/clockify/publish/development-checklist)

[Next PagePrivate addon deployment](/clockify/publish/private-addon-deployment)

ON THIS PAGE

-   [Guidelines & best practices](#guidelines--best-practices "Guidelines & best practices")
-   [What’s the criteria add-ons need to meet in order to be published?](#whats-the-criteria-add-ons-need-to-meet-in-order-to-be-published "What’s the criteria add-ons need to meet in order to be published?")
-   [What are general guidelines for add-on content?](#what-are-general-guidelines-for-add-on-content "What are general guidelines for add-on content?")
-   [Is there any type of content that is prohibited?](#is-there-any-type-of-content-that-is-prohibited "Is there any type of content that is prohibited?")
-   [Final note](#final-note "Final note")
-   [Create add-on](#create-add-on "Create add-on")
-   [How to create add-on?](#how-to-create-add-on "How to create add-on?")
-   [How does reviewing work?](#how-does-reviewing-work "How does reviewing work?")
-   [Create version & publish](#create-version--publish "Create version & publish")
-   [How to create new version?](#how-to-create-new-version "How to create new version?")
-   [Which status can version request have?](#which-status-can-version-request-have "Which status can version request have?")
-   [Delete add-on](#delete-add-on "Delete add-on")
-   [How to delete add-on?](#how-to-delete-add-on "How to delete add-on?")
-   [How to delete add-on version request?](#how-to-delete-add-on-version-request "How to delete add-on version request?")
-   [Data privacy guidelines](#data-privacy-guidelines "Data privacy guidelines")
-   [Clearly explain your data privacy practices](#clearly-explain-your-data-privacy-practices "Clearly explain your data privacy practices")
-   [Minimize the data you collect](#minimize-the-data-you-collect "Minimize the data you collect")
-   [Get consent for certain data use](#get-consent-for-certain-data-use "Get consent for certain data use")
-   [Provide access, modification and erasure of personal data](#provide-access-modification-and-erasure-of-personal-data "Provide access, modification and erasure of personal data")
-   [Offer additional data processing terms](#offer-additional-data-processing-terms "Offer additional data processing terms")
-   [Invest in data security](#invest-in-data-security "Invest in data security")
-   [Security requirements](#security-requirements "Security requirements")
-   [Authentication & authorization](#authentication--authorization "Authentication & authorization")
-   [Data protection](#data-protection "Data protection")
-   [Application security](#application-security "Application security")
-   [Privacy](#privacy "Privacy")
-   [Vulnerability management](#vulnerability-management "Vulnerability management")
