sed -i '/<span class="badge badge-${file.status}">${file.status}<\/span>/a \                            ${chunkProgressHtml}' src/main/resources/templates/dashboard.html

sed -i '/let actionsHtml = '"'"''"'"';/i \
                    let chunkProgressHtml = "";\n\
                    if (file.chunks && file.chunks.length > 0) {\n\
                        let total = file.chunks.length;\n\
                        let converted = file.chunks.filter(c => c.status === "SUCCESS").length;\n\
                        if (file.status === "CONVERTING" || file.status === "ERROR" || file.status === "NEEDS_REVIEW") {\n\
                            chunkProgressHtml = `<br><small class="text-info">EN COURS (${converted}/${total} chunks convertis)</small>`;\n\
                        }\n\
                    }' src/main/resources/templates/dashboard.html
