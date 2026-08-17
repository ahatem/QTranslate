# Third-party notices

QTranslate bundles work from the projects below. Each is used under its own licence, reproduced or
linked here as that licence requires. Nothing in this file changes the terms QTranslate itself is
distributed under.

## Icon sets

Icons live under `ui-swing/src/main/resources/icons/<set>/`, one folder per set, and are chosen in
Settings → Appearance. The names inside each folder are QTranslate's own vocabulary rather than the
set's, so a file called `edit.svg` is whichever glyph that set uses for editing.

| Set | Folder | Licence | Source |
|-----|--------|---------|--------|
| Lucide | `lucide` | ISC | https://lucide.dev |
| Material Symbols | `material-symbols` | Apache-2.0 | https://fonts.google.com/icons |
| Material Design Icons | `material` | Apache-2.0 | https://fonts.google.com/icons |
| Phosphor | `phosphor` | MIT | https://phosphoricons.com |
| Heroicons | `heroicons` | MIT | https://heroicons.com |
| Tabler | `tabler` | MIT | https://tabler.io/icons |

### Lucide — ISC

Lucide is a fork of Feather Icons. Copyright (c) 2020, Lucide Contributors. Copyright (c) 2013–2022,
Cole Bemis (Feather).

> Permission to use, copy, modify, and/or distribute this software for any purpose with or without
> fee is hereby granted, provided that the above copyright notice and this permission notice appear
> in all copies.
>
> THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES WITH REGARD TO THIS
> SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE
> AUTHOR BE LIABLE FOR ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
> WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT,
> NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE
> OF THIS SOFTWARE.

### Material Symbols and Material Design Icons — Apache-2.0

Copyright Google LLC. Licensed under the Apache License, Version 2.0. A copy is available at
http://www.apache.org/licenses/LICENSE-2.0. The icons are used unmodified except for being renamed
to QTranslate's icon vocabulary.

### Phosphor — MIT

Copyright (c) 2023 Phosphor Icons.

### Heroicons — MIT

Copyright (c) Tailwind Labs, Inc.

### Tabler — MIT

Copyright (c) 2020–2024 Paweł Kuna.

> Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
> associated documentation files (the "Software"), to deal in the Software without restriction,
> including without limitation the rights to use, copy, modify, merge, publish, distribute,
> sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
> furnished to do so, subject to the following conditions:
>
> The above copyright notice and this permission notice shall be included in all copies or
> substantial portions of the Software.
>
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT
> NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
> NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
> DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT
> OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

*(The MIT text above applies equally to Phosphor and Heroicons, with their respective copyright
holders substituted.)*

## A note on populating a set

A set does not have to be complete. Any name it lacks is served from Lucide, so a folder is usable
from its first icon. Only add glyphs that set actually publishes — the point of a set is that it
looks like itself, and borrowing one project's glyph into another project's folder defeats both the
consistency and the attribution above.
