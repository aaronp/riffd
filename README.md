# Building

<script defer type="text/javascript" src="src/gitbook/demo/riffjs-opt.js"></script>
<script defer type="text/javascript" src="src/gitbook/demo/main.js"></script>
<link href="src/gitbook/demo/app.css" />

# Docs
<div id="target"></div>
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

This is the manual script to do it:
```
http://sangsoonam.github.io/2016/08/02/publish-gitbook-to-your-github-pages.html
```