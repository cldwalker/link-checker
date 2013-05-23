## Description

TODO

## Running the App

1. Start the application: `lein run`
2. Go to [localhost:8080](http://localhost:8080/) and verify a url.

## Limitations

* Only works with browsers that support [Server Side Events](http://caniuse.com/#feat=eventsource) and [HTML5 History](http://caniuse.com/#feat=history).
* In Chrome, if you look up a couple of different users and then enter
  a direct user url e.g. /defunkt, going backwords and forwards in
  your browser will be wonky.
