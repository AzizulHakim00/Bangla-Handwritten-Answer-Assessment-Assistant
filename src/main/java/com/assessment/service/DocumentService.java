package com.assessment.service;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.Map;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class DocumentService {
    public final Path storage;
    public DocumentService(@Value("${assessment.storage:./data/scripts}") String path) throws IOException {
        storage=Path.of(path).toAbsolutePath().normalize(); Files.createDirectories(storage);
    }
    public Path path(String id) {
        if (!id.matches("SCR-[a-zA-Z0-9-]+")) throw bad("Invalid document ID");
        return storage.resolve(id);
    }
    public BufferedImage page(Map<String,Object> script,int page) throws IOException {
        if(page<1||page>500) throw bad("Invalid page number");
        Path file=path(script.get("id").toString());
        if("application/pdf".equals(script.get("mime"))) {
            try(var pdf=Loader.loadPDF(file.toFile())) {
                if(page>pdf.getNumberOfPages()) throw bad("Page is outside this document");
                var box=pdf.getPage(page-1).getCropBox();
                if(box.getWidth()<=0||box.getHeight()<=0||(double)box.getWidth()*box.getHeight()*4>16000000) throw bad("PDF page dimensions are too large");
                return new PDFRenderer(pdf).renderImageWithDPI(page-1,144);
            }
        }
        if(page!=1) throw bad("Images have only one page");
        try(var in=ImageIO.createImageInputStream(file.toFile())) {
            var readers=ImageIO.getImageReaders(in); if(!readers.hasNext()) throw bad("Image cannot be decoded");
            var reader=readers.next(); try {
                reader.setInput(in); long pixels=(long)reader.getWidth(0)*reader.getHeight(0);
                if(pixels<=0||pixels>16000000) throw bad("Image is too large; resize to under 16 megapixels");
                return reader.read(0);
            } finally { reader.dispose(); }
        }
    }
    public static BufferedImage crop(BufferedImage image,Map<String,Object> region) {
        double x=number(region,"x"), y=number(region,"y"), w=number(region,"width"), h=number(region,"height");
        if(!Double.isFinite(x+y+w+h)||x<0||y<0||w<=0||h<=0||x+w>1.000001||y+h>1.000001) throw bad("Region must fit inside the page");
        int left=(int)(x*image.getWidth()),top=(int)(y*image.getHeight());
        int width=Math.min(image.getWidth()-left,Math.max(1,(int)(w*image.getWidth())));
        int height=Math.min(image.getHeight()-top,Math.max(1,(int)(h*image.getHeight())));
        if(width<1||height<1) throw bad("Region is too small");
        return image.getSubimage(left,top,width,height);
    }
    private static double number(Map<String,Object> m,String k) { if(!(m.get(k) instanceof Number n)) throw bad("Missing region coordinate"); return n.doubleValue(); }
    private static ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
}
