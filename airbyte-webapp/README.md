# airbyte-webapp

This module contains the Airbyte Webapp. It is a React app written in TypeScript.
The webapp compiles to static HTML, JavaScript and CSS, which is served (in OSS) via
a nginx in the airbyte-webapp docker image. This nginx also serves as the reverse proxy
for accessing the server APIs in other images.

## Develop on airbyte-webapp
Spin up Airbyte locally in your local airbyte-platform repository so the UI can make requests against the local API.
BASIC_AUTH_USERNAME="" BASIC_AUTH_PASSWORD="" docker compose up

Note: basic auth must be disabled by setting BASIC_AUTH_USERNAME and BASIC_AUTH_PASSWORD to empty values, otherwise requests from the development server will fail against the local API.

Install nvm (Node Version Manager) if not installed
Use nvm to install the required node version:
cd airbyte-webapp
nvm install

Install the pnpm package manager in the required version. You can use Node's corepack for that:
corepack enable && corepack install

Start up the react app.
pnpm install
pnpm start



## Building the webapp

```sh
# Only compile and build the docker webapp image:
./gradlew :airbyte-webapp:assemble
# Build the webapp and additional artifacts and run tests:
./gradlew :airbyte-webapp:build
```
