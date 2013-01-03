import com.petebevin.markdown.MarkdownProcessor
import groovy.text.SimpleTemplateEngine
import java.text.DateFormatSymbols
import java.text.SimpleDateFormat
import java.util.regex.Pattern


class Generator {
    class Post {
        String title
        String name
        Date lastUpdated
        String summary
        String content
        List tags = []
    }

    class Tag {
        String title
        String name
        List posts = []
        String toString() { name }
    }

//    CliBuilder cl = new CliBuilder(usage: 'groovy rizzo -s "source" -d "destination"')
//
//    cl.s(longOpt: 'source', args: 1, required: true, 'Location of website source')
//    cl.d(longOpt: 'destination', args: 1, required: true, 'Location in which to place generated website')
//    cl.r(longOpt: 'regenerate', args: 0, required: false, 'Regenerate pages and posts')

    def cfg

    def charTable = ['а':'a', 'б':'b', 'в':'v', 'г':'g', 'д':'d', 'е':'e','ё':'e', 'ж':'zh', 'з':'z', 'и':"i", 'й':'i',
            'к':'k', 'л':'l', 'м':'m', 'н':'n','о':'o', 'п': 'p', 'р':'r', 'с':'s', 'т':'t', 'у':'u', 'ф':'f', 'х':'h',
            'ц':'c', 'ч':'ch','ш':'sh', 'щ':'sh', 'ъ':'\'', 'ы':'y', 'ь':'\'', 'э':'e', 'ю':'u', 'я':'ya'];

    def whiteSpaces = Pattern.compile('\\s+')

    def normalize = {String str ->
        StringBuilder sb = new StringBuilder(str.toLowerCase().replaceAll(whiteSpaces, '-'))
        StringBuilder out = new StringBuilder(sb.size())
        sb.toList().each { c ->
            out.append(charTable[c] ?: c)
        }
        out.toString()
    }

    def extractSummary = {String content ->
        def moreIdx = content.indexOf("<!--more-->")
        moreIdx > 0 ? content.substring(0, moreIdx) : null
    }

    def posts = []
    def tags = []

    def init(String source, String dest) {
        cfg = new ConfigSlurper().parse(new File("${source}/site-config.groovy").toURI().toURL())
        DateFormatSymbols dfs = new DateFormatSymbols(new Locale("ru"));
        String[] months = ["января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря"];
        dfs.setMonths(months);
        cfg.outWithMonthFormatter = new SimpleDateFormat("d MMMM yyyy 'г.' HH:mm", dfs)
        cfg.yearFormatter = new SimpleDateFormat("yyyy")
        cfg.monthFormatter = new SimpleDateFormat("MM")
        cfg.inFormatter = cfg.inFormatter ?: new SimpleDateFormat("dd-MM-yyyy HH:mm")
        cfg.outFormatter = cfg.outFormatter ?: new SimpleDateFormat("dd.MM.yyyy")
        cfg.itemIdDateFormatter = cfg.itemIdDateFormatter ?: new SimpleDateFormat("yyyy-MM-dd")
        cfg.base3339DateFormatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")
        cfg.zoneDateFormatter = new SimpleDateFormat("Z")
        cfg.source = source;
        cfg.dest = dest;

        cfg.rfc3339Format = { date ->
            def zone = cfg.zoneDateFormatter.format(date)
            cfg.base3339DateFormatter.format(date) + zone[0..2] + ':' + zone[3..4]
        }

        cfg.createPostPath = { post ->
            "${cfg.yearFormatter.format(post.lastUpdated)}/${cfg.monthFormatter.format(post.lastUpdated)}/${post.name}"
        }

        cfg.createPostLink = { post ->
            cfg.site.base+'/' + cfg.createPostPath(post)
        }

        cfg.createTagLink = { tag ->
            cfg.site.base + '/tags/' + tag.name + '/'
        }

        def destination = new File(dest)

        if (!destination.exists()){
            destination.mkdirs();
        }

        cfg.postFiles = new File("${source}/posts/")
        cfg.pageFiles = new File("${source}/pages/")
        cfg.baseTmpl = new File("${source}/templates/base.html")
        cfg.tagTmpl = new File("${source}/templates/tag.html")
        cfg.tagsTmpl = new File("${source}/templates/tags.html")
        cfg.indexEntryTmpl = new File("${source}/templates/entry.html")
        cfg.postTmpl = new File("${source}/templates/post.html")
        cfg.feedTmpl = new File("${source}/templates/feed.xml");
        cfg.entryTmpl = new File("${source}/templates/entry.xml")
        cfg.addThisTmpl = new File("${source}/templates/addthis.html")
        cfg.siteFeed = new File("${dest}/feed.xml")
        cfg.templateEngine = new SimpleTemplateEngine()
        cfg.mdProcessor = new MarkdownProcessor()
    }

    def process(){
        writePages()
        writePosts()
        writeTags()
        writeIndex()
        writeTagsIndex()
        writeFeed()
        writeStatic()
    }

    def writePages() {
        cfg.pageFiles.eachFileMatch(~/.*[\.html|\.md]/) { file ->
            def name = file.name[0..file.name.lastIndexOf('.') - 1]
            List pageText = file.readLines()
            def page = new Post(title: pageText[0], name: name, lastUpdated: cfg.inFormatter.parse(pageText[1].toString()))
            pageText = pageText[3..-1]
            page.content = file.name.endsWith('.md') ? cfg.mdProcessor.markdown(pageText.join("\n")) : pageText.join("\n")
            def model = ["content": page.content, "config": cfg, "commentsEnabled" : false, "title": page.title, "pages" : 1]
            def pagePath = "${cfg.dest}/${name}"
            new File(pagePath).mkdirs()
            new File("$pagePath/index.html").write("${cfg.templateEngine.createTemplate(cfg.baseTmpl).make(model)}")
        }
    }

    def writePosts() {
        cfg.postFiles.eachFileMatch(~/.*[\.html|\.md]/) { file ->
            def name = file.name[0..file.name.lastIndexOf('.') - 1]
            def postText = file.readLines()
            def post = new Post(title: postText[0], name: name, lastUpdated: cfg.inFormatter.parse(postText[1].toString()))
            List tagList = postText[2].split(", ") as List
            tagList.each { post.tags << new Tag(title: "$it", name: normalize("$it"), posts: [post]) }
            postText = postText[4..-1].join("\n")
            post.summary = extractSummary(postText)
            post.content = file.name.endsWith('.md') ? cfg.mdProcessor.markdown(postText) : postText
            posts << post

            post.tags.each { tag ->
                def currentTag = tags.find {it.name.equals(tag.name)}
                if (currentTag) {
                    currentTag.posts << post
                } else {
                    tags << tag
                }
            }

            def postModel = ["post": post, "config": cfg]
            postModel = ["content": "${cfg.templateEngine.createTemplate(cfg.postTmpl).make(postModel)}", "config": cfg, "commentsEnabled" : true,
                    "title": post.title, "pages" : 1]
            def postPath = "${cfg.dest}/${cfg.createPostPath(post)}"
            new File(postPath).mkdirs()
            new File(postPath + "/index.html").write("${cfg.templateEngine.createTemplate(cfg.baseTmpl).make(postModel)}")

        }
    }

    def writeTags() {
        new File("${cfg.dest}/tags/").mkdirs()
        tags.each { tag ->
            tag.posts = tag.posts.sort { it.lastUpdated }.reverse()
            def model = ["config": cfg, "posts" : tag.posts]
            String content = cfg.templateEngine.createTemplate(cfg.tagTmpl).make(model).toString()
            model = ["content": content, "config": cfg, "commentsEnabled" : false, "title": tag.title, "pages" : 1]
            def tagPath = "${cfg.dest}/tags/${tag.name}"
            new File(tagPath).mkdirs()
            new File("${tagPath}/index.html").write("${cfg.templateEngine.createTemplate(cfg.baseTmpl).make(model)}")

            def max = tag.posts.size() > 20 ? 19 : tag.posts.size() - 1
            def tagFeed = new File("${cfg.dest}/tags/${tag.name}/feed.xml")
            String entries = ""
            tag.posts[0..max].each { post ->
                def feedEntryModel = ["post": post, "config": cfg]
                entries += "${cfg.templateEngine.createTemplate(cfg.entryTmpl).make(feedEntryModel)}"
            }
            def tagFeedModel = ["config": cfg, "entries": entries, "feedUrl": "${cfg.createTagLink(tag)}/feed.xml"]
            tagFeed.write("${cfg.templateEngine.createTemplate(cfg.feedTmpl).make(tagFeedModel)}")
        }
    }

    def writeIndex() {
        posts = posts.sort { it.lastUpdated }.reverse()
        String indexContent = ""
        def counter = 0
        def page = 0
        posts[0..posts.size() - 1].each { post ->
            def postModel = ["post": post, "config": cfg]
            indexContent += cfg.templateEngine.createTemplate(cfg.indexEntryTmpl).make(postModel)
//    counter++
//    page = counter / cfg.postPerPage
//    if ((page > 0) && (counter % cfg.postPerPage == 0)){
        }
        def indexModel = ["content": indexContent, "config": cfg, "commentsEnabled" : false, "title": "", "pages" : 1]
        new File("${cfg.dest}/index.html").write("${cfg.templateEngine.createTemplate(cfg.baseTmpl).make(indexModel)}")
    }

    def writeTagsIndex() {
        def tagsModel = ["config": cfg, "tags": tags]
        def tagsContent = "${cfg.templateEngine.createTemplate(cfg.tagsTmpl).make(tagsModel)}"
        tagsModel = ["config": cfg, "content": tagsContent, "commentsEnabled" : false, "title": "", "pages" : 1]
        new File("${cfg.dest}/tags/index.html").write("${cfg.templateEngine.createTemplate(cfg.baseTmpl).make(tagsModel)}")
    }

    def writeFeed() {
//        max = posts.size() > 20 ? 19 : posts.size() - 1
        String feedEntries = ""
        posts.each { post ->
            def feedItemModel = ["post": post, "config": cfg]
            feedEntries += "${cfg.templateEngine.createTemplate(cfg.entryTmpl).make(feedItemModel)}"
        }
        def feedModel = ["feedUrl": "${cfg.site.url}${cfg.site.base}/feed.xml", "entries": feedEntries, "config": cfg]
        cfg.siteFeed.write("${cfg.templateEngine.createTemplate(cfg.feedTmpl).make(feedModel)}")

    }

    def writeStatic() {
        def ant = new AntBuilder()
        new File("${cfg.dest}/css/").mkdirs()
        ant.copy(todir: "${cfg.dest}/css/") {
            fileset(dir: "${cfg.source}/css/")
        }
        new File("${cfg.dest}/js/").mkdirs()
        ant.copy(todir: "${cfg.dest}/js/") {
            fileset(dir: "${cfg.source}/js/")
        }
        new File("${cfg.dest}/images/").mkdirs()
        ant.copy(todir: "${cfg.dest}/images/") {
            fileset(dir: "${cfg.source}/images/")
        }
    }
}