## Description

This app is a link checker that verifies all links for a
given url. Features include:

* Links are validated in parallel, taking advantage of as many cores as
  the server can provide.
* Only a section of a url's links can be verified by using a css
  selector (no whiteaspace).
* Validation results for a given url are linkable e.g. /result?url=URL&selector=SEL.
* By default, only invalid links are shown. Successful links can be
  toggled with the provided button.
* Links that exceed the 5 redirects limit have their redirect path
  appended to their status. To see this, hover over the status of a
  redirected link.
* After all links are validated, the total number of links fetched,
  number of unsuccessful (2XX) links and the total fetch time are displayed.

This app lives [on heroku](#TODO).

## Running the App

1. Start the application: `lein run`
2. Go to [localhost:8080](http://localhost:8080/) and verify a url.

## Limitations

* Only works with browsers that support [Server Side Events](http://caniuse.com/#feat=eventsource) and [HTML5 History](http://caniuse.com/#feat=history).
* In Chrome, if you look up a couple of different users and then enter
  a direct user url e.g. /defunkt, going backwords and forwards in
  your browser will be wonky.
