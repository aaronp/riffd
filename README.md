# Building

# Docs

I've used gitbook to generate the site, which can be configured as per:
```
https://github.com/GitbookIO/gitbook/blob/master/docs/config.md
```

To build/serve/test gitbook locally, I use 
```
https://github.com/billryan/docker-gitbook
```

Or online [here](https://app.gitbook.com/@aaron-pritzlaff/s/riff/@drafts)

(or e.g. )

e.g.:
```
cd src/git
docker run --rm -v "$PWD:/gitbook" -p 4000:4000 billryan/gitbook gitbook serve
```

and FYI, ```alias gitbook='docker run --rm -v "$PWD":/gitbook -p 4000:4000 billryan/gitbook gitbook'```