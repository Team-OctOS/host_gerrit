// Copyright (C) 2012 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.server.documentation;

import static org.pegdown.Extensions.ALL;
import static org.pegdown.Extensions.HARDWRAPS;

import com.google.common.base.Strings;

import org.eclipse.jgit.util.RawParseUtils;
import org.eclipse.jgit.util.TemporaryBuffer;
import org.pegdown.LinkRenderer;
import org.pegdown.PegDownProcessor;
import org.pegdown.ToHtmlSerializer;
import org.pegdown.ast.HeaderNode;
import org.pegdown.ast.Node;
import org.pegdown.ast.RootNode;
import org.pegdown.ast.TextNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.concurrent.atomic.AtomicBoolean;

public class MarkdownFormatter {
  private static final Logger log =
      LoggerFactory.getLogger(MarkdownFormatter.class);

  private static final String css;

  static {
    AtomicBoolean file = new AtomicBoolean();
    String src;
    try {
      src = readPegdownCss(file);
    } catch (IOException err) {
      log.warn("Cannot load pegdown.css", err);
      src = "";
    }
    css = file.get() ? null : src;
  }

  private static String readCSS() {
    if (css != null) {
      return css;
    }
    try {
      return readPegdownCss(new AtomicBoolean());
    } catch (IOException err) {
      log.warn("Cannot load pegdown.css", err);
      return "";
    }
  }

  public byte[] markdownToDocHtml(String md, String charEnc)
      throws UnsupportedEncodingException {
    RootNode root = parseMarkdown(md);
    String title = findTitle(root);

    StringBuilder html = new StringBuilder();
    html.append("<html>");
    html.append("<head>");
    if (!Strings.isNullOrEmpty(title)) {
      html.append("<title>").append(title).append("</title>");
    }
    html.append("<style type=\"text/css\">\n")
        .append(readCSS())
        .append("\n</style>");
    html.append("</head>");
    html.append("<body>\n");
    html.append(new ToHtmlSerializer(new LinkRenderer()).toHtml(root));
    html.append("\n</body></html>");
    return html.toString().getBytes(charEnc);
  }

  public String extractTitleFromMarkdown(byte[] data, String charEnc) {
    String md = RawParseUtils.decode(Charset.forName(charEnc), data);
    return findTitle(parseMarkdown(md));
  }

  private String findTitle(Node root) {
    if (root instanceof HeaderNode) {
      HeaderNode h = (HeaderNode) root;
      if (h.getLevel() == 1
          && h.getChildren() != null
          && !h.getChildren().isEmpty()) {
        StringBuilder b = new StringBuilder();
        for (Node n : root.getChildren()) {
          if (n instanceof TextNode) {
            b.append(((TextNode) n).getText());
          }
        }
        return b.toString();
      }
    }

    for (Node n : root.getChildren()) {
      String title = findTitle(n);
      if (title != null) {
        return title;
      }
    }
    return null;
  }

  private RootNode parseMarkdown(String md) {
    return new PegDownProcessor(ALL & ~(HARDWRAPS))
        .parseMarkdown(md.toCharArray());
  }

  private static String readPegdownCss(AtomicBoolean file)
      throws IOException {
    String name = "pegdown.css";
    URL url = MarkdownFormatter.class.getResource(name);
    if (url == null) {
      throw new FileNotFoundException("Resource " + name);
    }
    file.set("file".equals(url.getProtocol()));
    InputStream in = url.openStream();
    try {
      TemporaryBuffer.Heap tmp = new TemporaryBuffer.Heap(128 * 1024);
      try {
        tmp.copy(in);
        return new String(tmp.toByteArray(), "UTF-8");
      } finally {
        tmp.close();
      }
    } finally {
      in.close();
    }
  }
}
