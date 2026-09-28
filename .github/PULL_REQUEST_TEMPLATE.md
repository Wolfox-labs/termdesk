## Summary

What does this change do, and why?

## Component

- [ ] pc-agent
- [ ] android
- [ ] docs / CI / tooling

## Checklist

- [ ] `npm test` (static checks) passes in `pc-agent/`
- [ ] No secrets, tokens, or personal paths added
- [ ] Shell remains **disabled** by default; any test that needs `--enable-shell` is clearly marked
- [ ] Filesystem roots were not widened
- [ ] README / protocol table updated if frames changed
