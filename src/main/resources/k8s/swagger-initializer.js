window.onload = function() {
    window.ui = SwaggerUIBundle({
        urls: [ ],
        "dom_id": "#swagger-ui",
        deepLinking: true,
        presets: [
            SwaggerUIBundle.presets.apis,
            SwaggerUIStandalonePreset
        ],
        plugins: [
            SwaggerUIBundle.plugins.DownloadUrl
        ],
        layout: "StandaloneLayout",
        queryConfigEnabled: false,
        requestInterceptor: (e)=>{
            console.log(e)
            if( !e.loadSpec ){
                const service = document.getElementById("select").value;

                const split = service.split("/");
                const servicename = split[1];

                const current = e.url.split("/");
                const protocol = current.shift();
                const empty1 = current.shift();
                const domain = current.shift();

                e.url = `${protocol}//${domain}/${servicename}/${current.join('/')}`
            }
            return e;
        }
    })
};