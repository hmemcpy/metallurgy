# 13 — ScalaWAW #32: "The best Scala IDE inside your favourite Scala IDE" (transcript)

Auto-generated transcript (YouTube captions, cleaned of rolling-window repetition;
**not manually corrected**). The earlier Jędrzej prototype talk — running the Scala
Presentation Compiler directly inside IntelliJ, without LSP.

| | |
|---|---|
| **Event** | ScalaWAW #32 — *Hello Paramount!* (Warsaw Scala meetup) |
| **Date** | 2024-04-18 |
| **Duration** | full video 1h45m; **Jędrzej's talk starts at 01:06:04** (preceded by an unrelated Chimney talk by Mateusz Kubuszok) |
| **Speaker** | Jędrzej Rochala (VirtusLab / Scala Center — Scalac compiler, Metals, Scastie) |
| **URL** | <https://www.youtube.com/watch?v=SNc7xeHrKnQ> (Jędrzej at `&t=3931s`) |
| **Relation to Metallurgy** | This is the prototype Igal's LSP experiment and Jędrzej's later BETASTy demo (see `12-…`) built on. The classpath-population mechanism shown here is the one Metallurgy inherits. |

> Transcription caveat: auto-captions mishear proper nouns. Reading key:
> **intell / intj / intelligy / intelligent** = IntelliJ · **scolar / scy** = Scala · **medals** = Metals ·
> **PSI / PSIs** = PSI · **custy / tasty / sty** = TASTy/Scastie · **basty / betasty** = BETASTy ·
> **class P / class puff / class paff / class PA** = classpath · **flux** = flags · **eal / Eagle / eagal** = Igal ·
> **cotl** = Kotlin · **redut** = (redhat?) · **scalet Tre / scalar Tre** = Scala 3 · **vermut** = vermouth.
> Treat technical specifics as paraphrase, not quotation.

## Key moments

- **[67:53–69:57]** — The motivating gap: a transparent-inline macro (`typesafe-config`) whose typed fields
  Metals deduces but IntelliJ can't. Credits Igal's LSP hack (macro evaluation replaced by Metals) as "genius."
- **[70:26–75:11]** — Why IntelliJ fails: it has its **own** PSI + own type-checker (not the compiler's).
  Frames it as a trade-off: IntelliJ = best error recovery; Metals = aligned with the true compiler.
- **[75:47–79:00]** — The thesis: IntelliJ's **"compiler Diagnostics"** (CBH) already aligned *errors* with
  the real compiler; Metals' **BETASTy** is pushing *error recovery* up. "Crazy idea": merge both — pc for
  type-dependent features (completions, hover, signature help), IntelliJ indexes for the rest (go-to-def, find refs).
- **[79:22–79:48]** — Why **not** Igal's LSP approach: it runs the full Metals BSP — "another compilation
  server on top of IntelliJ… second most resource-heavy application after Chrome." pc-direct avoids the duplication.
- **[80:21–81:27]** — The **Scala 3 presentation compiler** Java API (`scala.meta.pc`): completions, hover,
  signature help, go-to-def. Notably *no* find-usages — "compiler is not so good at this; indexes are better."
- **[83:20–83:29]** — pc is **version-pinned** (e.g. 3.3.3); a real plugin dynamically loads it per module version.
- **[90:07–91:41]** — **THE classpath-population mechanism** (see quote below): paths from IntelliJ API
  (libs + module target folders); the target folders need actual class files, which PSIs don't produce; CBH
  runs incremental compilation on save and writes them. *"With this single feature we have handled the
  incremental compilation which is necessary for presentation compiler to work."*
- **[92:17–99:43]** — **Symbol search**: pc can't resolve not-yet-imported symbols (e.g. `ListBuffer`); solve
  by implementing pc's `SymbolSearch` interface backed by IntelliJ's `PsiShortNamesCache` index, converting
  PSI → SemanticDB symbol.
- **[100:38–104:26]** — Status: proof of concept, "non-invasive, doesn't duplicate work," "super slow…
  but still faster than IntelliJ when I benchmarked."

## Jędrzej on classpath population — [90:07]–[91:41]

> …we are required to provide a name for this presentation compiler… full classpath and scalac options. And
> where are we getting full classpath from? …this is the problem, we need a full classpath. IntelliJ luckily
> allows us to quickly get this from their API — because IntelliJ, it has to understand your projects, it has
> access to this information, we just need to access them for our case.
>
> So with this full classpath we will get something like this: we have library dependencies, which is a Scala
> library and Scala 3 library, and we also have **module dependencies. And this is quite problematic, because
> this is actually a target folder — so this is a folder which stores the class files produced by your build
> tool, and if you have multiple modules you need to have class files for them for the presentation compiler
> to work.**
>
> And with the PSIs it is quite hard, because **PSIs don't produce class files — they don't need to, they
> operate in memory.** But luckily IntelliJ introduced this great feature I mentioned before — the **compiler
> Diagnostics** — and I suppose they run full incremental compilation in the background, because **when this
> is enabled it actually produces class files when you save the file, so all the time. So we are always up to
> date with the changes in the files.** And with this single feature we have handled the incremental
> compilation which is necessary for presentation compiler to work.

## Full transcript (Jędrzej's talk, from 01:06:04)

```
[66:04]  thank you for the introduction I will introduce myself again in one second but uh I welcome you to my talk it is a very uh talk that
[66:13]  I made in a very very quick iterations I made it on Saturday uh so uh I called it the best ID is your favorite ID but I
[66:23]  think we can call it the best ID in your favorite ID because it will better match what I'm trying to show so uh I personally work uh
[66:36]  in virtu lab and I work at Scala Center where I try to work improve Scala language um and if I were to quickly describe what I do
[66:45]  uh at those organizations uh I listed some organizations I belong on GitHub and I do different stuff uh in each of them so first one is Scala
[66:57]  language so I a Scala free compiler engineer in this organization at Vitus lab I was intellig plug-in developer at Scala JS organization I was Scala JS frontend
[67:09]  developer uh in metals I'm an tooling engineer and at Scala Center I'm a buend developer working on scy so let's begin and uh yeah this is basically
[67:20]  a summary of what I just said I work on various tools and as I said previously one of them is custy uh I introduced completions in this
[67:32]  tool and uh as you can see in this example they just just work we also have Metals the same snippet almost the same we erupted in the
[67:45]  function the main method and it also works this is a very simple snippet right just a PR and we have intj and it also works no surprise
[67:53]  why why wouldn't it work yeah but this is not your average project uh right I I hope this is not your average project because in your average
[68:04]  project you have something special and this something special is a macro and what is uh exactly happening in this case is a transparent inline macro I don't
[68:15]  want to explain how it works because this is not the point of my talk Pavo marks Did a great explanation in his talk but what I'm trying
[68:25]  to show is that this macro which basically takes a map of strings and some other values get get transformed into a typed version so in this example
[68:36]  we can see that we try to run this macro type save config with uh two keys and values right so first is domain which we set to
[68:46]  scity and then is aort set to some number so uh metals can deduce what type are those uh Fields after the type checking so if you look
[69:00]  closely at the completions the second and third item is typed element domain of type string because the domain is provided as string and Deport is int because
[69:11]  we provideed is at int uh and in micro definition it was any so metals can deduce this and uh intellig Sly can do this and uh I'm
[69:26]  not here to uh tell oh it it can't work in intellig intellig is worse I'm here to show you that uh it can be solved and uh
[69:35]  it was already done One Week Ago by eal so he pled Metals uh via LSP which is available only in uh intell altimate Edition so this kind
[69:49]  of work his solution was genius when I first saw it I was like how did I not think of this so what he did is he not
[69:57]  plucked full metals and replaced intell he replaced macro evaluation with Metals so only macros were parsed by uh metals and all the other stuff was still working
[70:14]  from from intell so this is a pretty nice hug I like it very much but let's let's ask ourselves a question uh why did intj fail in
[70:26]  the first place because this this should work right the tooling should work so I will do a quick recap how compilers work I need to do this
[70:38]  so we can get the underlying problem so uh compilers uh have to do some iterations about they mostly do a paring and then type checking they can
[70:52]  do also other stuff but those are two BAS physics that have to happen so uh let's try to parse this second line of this code uh and
[71:01]  uh see how it looks like so we have this uh Godfather series Val value and we try to access a member head so in as which is
[71:16]  abstract syntax Tre uh a par form of this code it will be a selection from the identifier Godfather series to the identifier head so it's very simple
[71:30]  right let's get and make this example a bit harder so now we change it to get and now we provide some additional arguments to this uh method
[71:42]  and it is no longer a selection it is now an apply which takes selection and provided arguments so this is basically how the tree would this code
[71:51]  would look like in the internal comp F representation right but this is only the first phase the second phase is part uh typing type checking so in
[72:00]  order to type check this we have to also do some her istics or algorithm so uh in Scala it would look soic like this first we would
[72:12]  go into the left branch of this tree and ask ourself okay what are we trying to select so we are trying to select something from a Godfather
[72:21]  series uh I identifier so what is the type of this identifier it is basically Godfather series.
[72:30]  type and why it is not a list of strings because godfather. type is actually a subtype of list of strings it is in Singleton type and this
[72:44]  is the most correct type that we can get in the compiler right now so if we go further up to this up the tree we will can
[72:51]  get another type so now we want to select a method get from this uh identifier so the type now belongs to uh is Godfather series dog.
[72:59]  type it is also a correct type and now we have done the left uh side of this tree let's go to the right side and uh we
[73:08]  have to type this argument so this is zero zero is also a sylon type so it's zero.
[73:14]  type and now we know that it's application of integer argument to Godfather dog.
[73:20]  type so uh underlying type of this expression is integer into T but if we Dias this it's integer into string because it is the least of strings
[73:33]  so finally we can get the underlying type string yeah sorry for decid had to do this uh this explanation because I will need this uh later on
[73:45]  this is not super important to understand this is just a showcase how it works so intellig does stuff a bit differently because they have their own IST
[73:56]  it's called PSI uh this is Scala abstract syntax tree and this is program structure interface it is used in their tooling entirely so Java plug-in rust plug-in
[74:07]  C plugin everything works on those PSIs it is the Bas uh basic building block uh for all the features references go to definition uh the linting you
[74:21]  get uh which tells you okay you used filter and had option you could just use find uh it is all built on PSIs so if we check
[74:33]  those structures we can see that they're actually a bit different and it is obvious that uh if something is different we can't run the exact same algorithm
[74:43]  as we had before so we can't run Scala typer on those PSI because this is a different structure it won't work it won't compile it won't type
[74:53]  check so they had to not only Implement own parser they also had to implement own type Checker so uh this is not a bad solution this is
[75:02]  a solution which is a bit more expensive but it provides you a different kind of benefits so this is not bad this is at trade of and
[75:11]  if I were to create two axess and basically Place metals and intellig on those axis I would say that intellig as of today has very best error
[75:26]  recovery it just works it loads your PSIs into the memory it knows how to recover from error and how to find those error uh definitions and metals
[75:36]  on the other hand are not so good at this but they run true compiler underneath so you are almost always correct there are bugs obviously but you
[75:47]  are aligned with the compiler with intellig you are aligned to the their reimplementation of the plug-in but some time ago intj did a great thing in my
[75:58]  opinion so they added something called compiler Diagnostics and this is a big change which improved Scala free experience in the ID so basically they are no longer
[76:08]  providing you the red lines with the errors from the data type Checker they are using a true compiler to provide them to you you so the errors
[76:21]  are now completely aligned and that is a big benefit because you are no longer getting false positives and false negatives and in metals yeah and because of
[76:32]  that we can move intellig a bit to the right because it is now still uh not completely correct because completions hovers other features rely on their PSIs
[76:43]  but the errors are finally aligned and it's a big deal and with Metals we did something similar but we are going up with the error recovery so
[76:55]  there is an initiative called be tasty uh bet uh best effort tasty and it is uh basically a way to store trees with errors uh in a
[77:07]  pickled form which then can be picked up by the ID and used uh by the ID so this means in short that Metals will start working on
[77:21]  projects that are not compiling at least we hope it will start working on projects are not compiling but with this change uh it will move up so
[77:26]  we can go I think one step further and I had this crazy idea so maybe we can do something like this and merge them together and we
[77:39]  can selectively choose what we want to do from intellig and what we want to do from metals because some things are still better in intj some things
[77:49]  are better in metals let's just pick what's the best of both both tools so where am I going with this not all features require require typer and
[77:59]  those that do are really depending on the true representation which we saw in the beginning with those macro example it required us to have the real representation
[78:10]  and intj can provide it to us right now it will in the future for sure they will fix this but as of today it can't and metals
[78:16]  can do this and some other features are not so reliant on type checking so let's go first over features that require proper types so these are the
[78:29]  completions completions hovers signature helps this helps you the arguments which are required for the method and the other features that don't require they can be enriched with
[78:39]  the intell indexes which are very good so go to definition or find references references yeah so uh m can enrich intellig and I think intellig can enrich
[79:01]  Metals let me take a seep of water my solution or uh yeah intell is open source intell plugin is open source yeah so I did a thing
[79:22]  uh I implemented this this and uh Eagle also did this but his solution while it's ingenious and I really like this it has one drawback it runs
[79:38]  full metal CSP in the background so this is another compilation server on top of intellig which is actually believed to be uh second most uh resource heavy
[79:48]  application after Google Chrome and metals just before Metals so yeah yeah [Music] [Music] uh let's go back again and check those examples the simple examples they just
[80:02]  work they have the completions print lenss we again in metals have print lenss in intj we have those harder in vs code have those harder uh example
[80:10]  with macro in intell we also have this with proper adjustments but there is one thing that connects all of those pictures and this is called Scala free
[80:21]  presentation compiler bootstrapped and this is also another initiative taken by the Scala compiler team one year ago when we decided that we should Implement a presentation compiler
[80:33]  directly in the compiler previously it was a part of metals now we moved it back and it is published uh along the compiler itself just like Scala
[80:44]  Scala Library Library so this file this Library provides you the basic utilities for a basic uh ID all the requ required things that you have to implement
[80:55]  in order to that you can use to implement the IDE so first of them is the completions and this is the API this is Java API uh
[81:04]  it is completable future which uh it returns completable future of completion list and requires offset params we have hover signature help go to definition and Sly we
[81:17]  don't have find usages because it turns out compiler is not so good at this uh there are better ways to do this at least uh for example
[81:27]  indexes and yeah and with this uh Library this this package we can try and develop our solution so right now I will do a quick oneone how
[81:41]  to write intellig J plugins and at the end of this talk we should be able to give you a completion from Metals which don't duplicate work like
[81:51]  uh LSP does so I did this CH GPT which turns out that is awesome at writing intell plugins I was surprised I couldn't believe it but it
[82:01]  is so good at writing inell plugins I'm not sure maybe it's because I used Java instead of Scala to do this I mean to to to write
[82:10]  the examples prompts but it it helped me out really really well so in order to create a plugin we have to provide it with few necessary things
[82:22]  so first we obviously have to set up up a build SBT like in every Scala project so this is a bit different build SBT because it requires
[82:28]  us to provide an SBT idea plugin and also it allows us to provide so necessary stuff that will be um passed to the plugin the informations like
[82:40]  version platform so this plug-in is uh targeting idea Community uh from the version version 2.4.1 and uh it runs on Scala 213 sadly I don't know why
[82:52]  Scala 3 doesn't work with intell J I suppose this is the the reason is SBT plugin and also we can pass intell plugins so this is just
[83:00]  like Library dependency this is plugin dependency and we want to depend on Java plugin and Scala plug-in because they have all the stuff that we want to
[83:08]  reuse right and we also add this presentation compiler so in normal application we wouldn't add this right here we would dynamically loaded for your specific version because
[83:20]  as you can see this is a presentation compiler fre. 3.3 it is specifically for this version of the compiler you can't use this one with any
[83:29]  other version so you have to be very care careful about this but for the sake of this presentation let's just use 3.3.3 and the second file unit
[83:38]  is plug-in XML so it also is a description of your plugin but the important thing here is the line 11 13 uh 213 which allows us to
[83:52]  add extensions and those extensions will be places where we PLU our new ID features en reached ID features so let's do this and let's try to create
[84:01]  our own completion provider so we will start with creating a final class it is required to be final by the intell uh PC completion provider which extends
[84:16]  completion contributor so completion contributor is provided to us by intell J open API and it has method fi fi completion variants we will get to this in
[84:26]  the second but first let's look that this is actually working then normal person would shot a request to emdb or ask database for Paramount movies but I
[84:37]  can't do this right here because I'm too lazy I just asked CH GPT to list those movies for me and I hardcoded them but you see only
[84:45]  one uh and then we can finally overwrite this method so this method runs at the end of the completions it is used to enrich add more of
[84:56]  them delete some of them depending on your use case and here we just needed this so it gives us parameters which is the file uh the cursor
[85:06]  position all the necessary means to know what file are we trying to complete on and it also has result and the result is a completion result set
[85:15]  and uh right now we are diving into Java Scala we are no longer in this perfect world of immutability now we have side effects all the time
[85:27]  because the only way to uh modify this completion list is to modify the result because the this method returns a unit so this is not perfect but
[85:35]  we have to work with this so let's try to transform those movies uh into something uh nice so for each of those movies we have the title
[85:46]  and the year of this of when it was published then we want to create a lookup element Builder this is completion item in the ID single completion
[85:53]  item with a title and then we want to add a type type text so type text is something that is shown on the right of the completion
[86:00]  item box so we will see this in a second no worries uh we then add this to the list and we stop the completions to not run
[86:08]  any further to not propagate to other Runners providers so after this simple code is run we also have to provide this extension point so we say this
[86:16]  is completion contributor for language Scala and this is implementation class obviously everything is string why not and uh it should be loaded first and we will get
[86:29]  something like this we'll have all the movies uh from the Paramount uh which we can complete uh directly in ID so we have created a very simple
[86:36]  plugin which does a very simple thing and this is the first step to our solution because now we need to create something real we now need to
[86:45]  compute real completions and I'm sorry for the later parts of this uh talk this will be a bit of code but I have to show it to
[86:55]  you it won't be much but it will be some code so let's dive into this I would try to go slow and try to explain every line
[87:08]  yeah so as I as the comment says we want in this place computer completions so at the first top lines I uh added the signature of the
[87:20]  completion method from the presentation compiler so it takes uh offset params and returns completion list we want this so first thing we need is actually presentation compiler
[87:32]  so we can call this method let's not implement this right now we will do this later on we also need offset params Let's ignore those this is
[87:41]  basically a file the text and the offset offset is cursor position uh then we want to run these completions and store them somewhere and this this is
[87:54]  basically it but now we want to transform them sorry for doing a wait on this completable future but you can do this better but for the sake
[88:02]  of this presentation I wanted to keep it short and it is not so short in when you have everything mutable and uh yeah I'm doing ka8 it
[88:11]  works for this case but it should be Rewritten to to the assing version so uh when we finally await those compl itions we can get this completion
[88:25]  item list we can get items to get each completion item like this title and the year of this publishment and then we can transform it into intell
[88:35]  J compatible format so it is lookup element and we add it to the list so right now we have the completion St Metals but we still have
[88:43]  to provide the offset params and the presentation compilers so with offset parames I said this is quite simple we just pass a file U we pass the
[88:52]  text of this code snippet that we are trying to complete on and the parameters offset cursor position and then we need to get presentation compiler so in
[89:02]  this snippet I used a helare method compilers get presentation compiler but it is not implemented so let's try to implement this and yeah it is object don't
[89:12]  worry that is final class it is object uh and you can have multiple modules so let's uh start with the concurrent safe structure in the beginning let's
[89:25]  create a treap which takes as a Kia module this intell module so it is just SBT module basically and it holds a presentation compiler reference and then
[89:38]  let's create a safe accessor maybe something like this which creates uh a presentation compiler if it's not present and returns it to you yeah so how presentation
[89:51]  compiler is actually actually created so uh we just run new scolar presentation compiler and we instantiated with a method new instance and we require to provide we
[90:07]  are required to provide a name for this presentation compiler this is not important full class puff and Java C options and where are we getting full class
[90:20]  puff from we will get see in a second but this is the problem we need a full class puff intell luckily allows us to quickly get this
[90:28]  uh from their API because intell allows you it has to understand your projects it has access to this information we just need to access them for ours
[90:37]  our case uh so with this full class path we will get something like this so we have Library dependencies which is a scalar library and Scala free
[90:49]  library and we also have module dependencies and this is quite a problematic because this is actually a Target folder so this is a folder which stores the
[91:01]  class files produced by your build tool and if you have multiple files you need to have class files for them for presentation compiler to work and with
[91:12]  the PSIs it is quite hard because PSIs don't produce class files they don't need to they operate in memory but luckily intell J introduced this great feature
[91:22]  I mentioned before the compiler Diagnostics and I suppose they run full incremental compilation in the background because when this is enabled it actually produces class files uh
[91:34]  when you save the file so all the time uh so we are always up to date with the changes in the files and with this single feature
[91:41]  we have handled the incremental compilation which is necessary for presentation compiler to work and well now we can run the completions uh you can also pass them
[91:55]  but via the uh Constructor not the different method not here I'm ignoring them here for the sake of this presentation right and uh we can see some
[92:08]  completion so we have print F we have print Ln and those are provided by the actual presentation compiler provided by Scala yeah so wait that was it
[92:17]  it was so simple uh kind of but there is one problem that we still have to solve so this snippet doesn't work and why is that it
[92:31]  turns out that least buffer is not in the scope so how presentation compiler can know that it can complete complete least buffer which we expect in this
[92:41]  exact scenario so this is where index uh goes into help and in metals we have our own indexes inj have their own indexes and they're kinda not
[92:53]  compatible with each other so how can we approach this this is not so trivial to generate index it takes a long time when you start your project
[93:01]  to index all the dependencies and everything so I tried integrating it and it turns out there is a way there is always a way because presentation compiler
[93:11]  is not the only interface that is published via the compiler it is we also provide an interface called symbol search and it requires for us to provide
[93:21]  a way to get documentation for a symbol definition for the symbol definition source stop levels this is not important search method which is used to search a
[93:35]  given query in the symbol search search method is the same but Ford methods and yeah it also takes symbol search visitor this is a visitor which goes
[93:46]  over those class files and take symbol if they are matching your query or not so we can just keep it I will show it for you in
[93:54]  a second and uh this is what we want to implement we need to find a way to get a implemented visit workspace symbol and a method search
[94:04]  to make it work with the presentation compiler uh I know this because I implemented this uh this is not so this is basically uh an Insight uh
[94:18]  but it can work and to do this we have to understand what is this symbol because it is said this is the string but what string it
[94:29]  is like a path to this thing or something so and uh sorry so is not working yeah uh sorry for this my slides got mixed up so
[94:48]  this is uh a symbol this is semantic DB symbol so this is also something that was created by Scala meta organization and in Scala 2 it was
[94:56]  an external plugin which was added to the uh to your build and in Scala fre this is built into the compiler so it allows us to quickly
[95:07]  store references during compilation what is used where so then we can find them very very quickly and let's quickly try to understand how it works so in
[95:17]  the first line of this code we have object test and semantic DB output of this is a empty package with the object test at Dot and it
[95:26]  also stores occurrence that in the first line from uh character 7 to 11 uh we are using this test and it does this for all the symbols
[95:38]  so in the main method we will store that there is a symbol test.
[95:43]  Main and we will also store a definition of a test main arguments so we also store all the occurrences not only test main but also R string
[95:53]  unit because they are used on those lines and we also have print in the next line so this is basically a semantic DB this is semantic DB
[96:02]  symbol for a string and what we want to do is we want to take inell symbol uh which is actually a PSI element PSI from the uh
[96:15]  program structure interface tree the internal representation and turn it into compiler internal symbol and in order to do this we have to do something crazy we have
[96:24]  to ask CH GPT for help and uh ask him to write a method which allows us to convert PSI class to semantic DB symbol so this method
[96:34]  won't work but it works for some cases uh so I used it anyway uh just to Showcase you that least buffer will work uh so basically uh
[96:45]  we can from from this class it holds enough information to get a qualified name is a name with all the package prefixes which uh are on those
[96:58]  name uh on the symbol so then we replace them with dots or or slashes and it will just work for classes yeah and now we have to
[97:09]  provide and uh those uh the instances of those symbol searches so let's do this we will create intellig symol search which takes PSI short name sket so
[97:21]  this is cach provided to us by intj it does this on the startup you know this when you have this line on the bottom right corner indexing
[97:31]  this is when cach gets populated and then we want also a Search scope so we can limit the search to module project file anything we want yeah
[97:40]  now we have to provide this method search which I told you that we have to provide this and uh it takes a query this is the completion
[97:49]  prefix so in the Leist buffer example it will be list B not completed version uh we need build Target which we can ignore in this example and
[98:02]  a visitor which is provided to us from the compiler so let me also take a seep yeah so right now with those with this method we can
[98:22]  try and get those classes so I did this very very unoptimal in a very unoptimal way so first we ask if the query is long enough to
[98:32]  even bother searching the index it is very very uh hard operation to do let's try to limit uh when we do this then we will get all
[98:42]  matching classes so we get all class files names and then we filter those which start with the query so it will get all class files which are
[98:50]  seen by the intellig okay and it will return all that start with list B and now we want to get the PSI element of this class because
[99:02]  we need a full symbol to get PSI element and from this full symbol we can run our helper method to convert this to semantic DB symbol and
[99:11]  then finally we can run our visitor with semantic DB symbol provided other arguments are not important here and we are at the end we can now write
[99:22]  those few lines we can instantiate our cache so we get the instance of the intell cache we create our scope which is module with dependencies and Library
[99:33]  scope and finally we instantiate our intellig simple search and the only thing we need to do when we have this index is to run another completion another
[99:43]  Builder method with search and when we provideed this it just works and we have Leist buffer completed from the Intel EJ uh index yeah and I think
[99:54]  that's it oh yeah and the harder example also works uh so this is a proof of concept I have created sometime at Saturday and I was motivated
[100:07]  by eagal work and I think this is very very promising because I'm not here to neglect that intell J or metals are better I'm here to show
[100:16]  that together we can do something something better together and one way to do this would be maybe doing a fallback to metals or fallback to intell uh
[100:25]  when uh it is not capable of completing your your code so the future will show uh this is just a proof of concept but as you can
[100:38]  see it is possible to integrate both Tools in noninvasive matter which doesn't duplicate the work done uh by the LSP yeah so thank you and these are
[100:49]  my slides so if any anyone has any questions feel free to ask them will there be any uh page with reference uh where to find if this
[101:03]  idea will be usable like uh I would like to have more support okay so if I will have more information because as I said I haed this
[101:17]  during the weekend uh you will know by the conferences because I will talk about it a lot yeah but other than that probably Scala space space Twitter
[101:27]  will be the best place to do this it will end up there for sure if anything changes in this matter thank you a little bit more general
[101:42]  question I guess what is the best language to write intellig plugins with oh that's a tough one I'm not that experienced but you can do this with
[101:54]  Scala and you will not have your life harder it will fully intellig Scala plugin is written in Scala and it is super big plugin so I guess
[102:02]  in any jvm language it is possible uh but they provide the ues for Scala J Java and cotl obviously cool thanks yeah okay uh so y just
[102:16]  maybe one question from me so can you confirm this is happening because on the live stream somebody already suggested a name metal J for that so I
[102:28]  don't want to steal the I don't want to uh foresee anything because as I said this is just a proof of concept uh but yeah you you
[102:41]  can run Metals in intell already with the ultimate version and also I know that redut is doing a community version of the LSP protocol which will also
[102:52]  run to run Metals without ultimate version so I don't know I can't answer this question I I just don't know we will see in the future okay
[103:03]  one question um because it's like run Metals in intell I'm more interested in the other way round so can I I used to run a headless eclipse
[103:13]  and use that in Vim so can I run maybe in the future some headless andj get their PSI and actually have fun in vim and my terminal
[103:24]  setup you can uninstall IDF plugin right yeah but other than that I'm not sure I I I don't want their gooy that's like you have to ask
[103:34]  intellig team okay that's fine you have to ask intellig team I don't know okay but that approach is more interesting you know yeah because it would give
[103:43]  kind of the nice things that they provide and actually provide this back to kind of like an open community so what what I see is the most
[103:51]  prom here is the eagal hug so uh replacing Scala expression macro expression evaluator with the metals and in his PC it worked for some cases for some
[104:04]  it didn't but yeah it is just just a very promising idea and we will see how it develops as I said this is just a proof of
[104:15]  concept this is nothing serious uh it is super slow right now because as you as you've seen this code is very unoptimal I did awaits uh I
[104:26]  fetched all symbols from the cach this can be done way better but it still is faster than intell I think when I tried to Benchmark this so
[104:38]  yeah okay any more like extra questions after questions no okay so thank you andj very much you deserve a glass of vermut that's for sure and also
[104:46]  a round of applause of course thank you very much much [Applause] [Applause] so that's it for today so thank you for attending also another Applause for our
[105:01]  host uh Paramount thank you yeah uh you had your beer and pizza and now you have to pay for that by filling out this form uh it's
[105:13]  uh little a bit of feedback for us how to make this uh meetings more interesting so please fill that uh out and yeah we'll be heading for
[105:29]  um some more beers at hito zavia we'll meet in the corridor downstairs and we can go together or you can just walk yourself there it will be
[105:40]  fun so I suggest you go there uh yeah so thank you very much make sure you don't leave anything behind and yeah have more fun tonight thank
[105:50]  you
```
