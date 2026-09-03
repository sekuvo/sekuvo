# Contributing

Sekuvo is a single-maintainer project. The rules below exist so that it can
stay open *and* keep every distribution door open — including one, an App
Store build, that GPLv3 on its own cannot walk through. They were written
before the first outside contribution on purpose: the door only stays open if
no line in the repository ever arrives without the grant described here.

## Two things every contribution needs

**1. Sign-off (DCO).** Every commit carries a `Signed-off-by: Name <email>`
line (`git commit -s`). That line certifies the
[Developer Certificate of Origin 1.1](https://developercertificate.org): you
wrote the change, or you have the right to submit it under this project's
license.

**2. Relicensing grant.** By submitting a contribution you license it under
GPLv3 like the rest of the project, **and** you grant the project maintainer a
perpetual, worldwide, irrevocable, royalty-free right to also distribute your
contribution under other license terms. You keep your copyright. Contributions
without this grant are not merged into `main`; if you do not want to grant it,
say so in the pull request and we will talk first.

## Why the grant, in plain words

GPLv3 binds licensees, not the copyright holder. As long as every line in the
repository is either the maintainer's own or covered by the grant above, the
source stays GPLv3 for everyone and the maintainer can still publish a binary
under different terms where a store requires it. VLC was pulled from the App
Store in 2011 because one contributor's GPL-licensed code had no such grant;
it took a relicensing of the whole codebase to return. The moment a single
contribution lands here without the grant, that door closes the same way —
which is why this file exists now and not later.

## Third-party code

Do not add dependencies licensed GPL-only (GPL without a linking exception,
or AGPL). Apache-2.0, MIT, BSD, MPL-2.0 and LGPL are fine. The reason is the
one above: a GPL-only dependency binds every build that includes it.

## Everything else

- Open an issue before a large change; small fixes can go straight to a pull
  request.
- An exploitable security bug goes to `contact@sekuvo.com`, not to a public
  issue.
- Tests are JVM unit tests (`./gradlew testDebugUnitTest`). A change to
  `core/` or `backup/` comes with one.
