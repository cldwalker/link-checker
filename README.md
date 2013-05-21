## Description

This app shows a github user's authored repositories and [active
forks](#active-forks). This allows you to see interesting stats about
how responsive the author is to issues, how many issues they've
resolved, how many issues they have, etc.

View the app [on heroku](https://link-checker.herokuapp.com).

## Running the App

1. Start the application: `GITHUB_AUTH=user:pass lein run`
2. Go to [localhost:8080](http://localhost:8080/) and look up a user's contributions.

## Configuration

This app takes the following environment variables:
* `$GITHUB_AUTH (required)` - This can either be your Github Basic auth
  `user:pass` or an oauth token.

## Limitations

* Only works with browsers that support [Server Side Events](http://caniuse.com/#feat=eventsource) and [HTML5 History](http://caniuse.com/#feat=history).
* In Chrome, if you look up a couple of different users and then enter
  a direct user url e.g. /defunkt, going backwords and forwards in
  your browser will be wonky.

## Active Forks

Active forks are forks that have diverged from the parent. In order to
accurately capture all active forks and minimize API calls, here's the
criteria currently used:

* First find any forks that have at least 2 watchers or any open
  issues.
* From these forks, look for forks that have a later last pushed_at
  date than the parent or a different name than the parent.
