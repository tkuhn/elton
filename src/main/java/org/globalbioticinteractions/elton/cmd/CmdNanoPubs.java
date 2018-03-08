package org.globalbioticinteractions.elton.cmd;

import com.beust.jcommander.Parameters;
import org.apache.commons.lang.StringEscapeUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.eol.globi.Version;
import org.eol.globi.data.NodeFactoryException;
import org.eol.globi.domain.InteractType;
import org.eol.globi.domain.Interaction;
import org.eol.globi.domain.Specimen;
import org.eol.globi.domain.Study;
import org.eol.globi.domain.Taxon;
import org.eol.globi.service.Dataset;
import org.eol.globi.service.DatasetFactory;
import org.eol.globi.service.DatasetFinderException;
import org.eol.globi.service.GitHubImporterFactory;
import org.eol.globi.util.ExternalIdUtil;
import org.globalbioticinteractions.dataset.DatasetFinderLocal;
import org.globalbioticinteractions.elton.util.IdGenerator;
import org.globalbioticinteractions.elton.util.InteractionWriter;
import org.globalbioticinteractions.elton.util.NodeFactoryNull;
import org.globalbioticinteractions.elton.util.SpecimenTaxonOnly;
import org.joda.time.DateTimeZone;
import org.joda.time.format.ISODateTimeFormat;

import java.io.PrintStream;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

@Parameters(separators = "= ", commandDescription = "Generate Nanopubs Describing Interactions in Local Datasets")
public class CmdNanoPubs extends CmdInteractions {
    private final static Log LOG = LogFactory.getLog(CmdNanoPubs.class);
    private Date date = null;
    private IdGenerator idGenerator = () -> UUID.randomUUID().toString().replaceAll("-", "");

    void setDate(Date date) {
        this.date = date;
    }

    private Date getDate() {
        return date == null ? new Date() : date;
    }

    void setIdGenerator(IdGenerator idGenerator) {
        this.idGenerator = idGenerator;
    }

    public class NanoPubWriter implements InteractionWriter {
        private final PrintStream out;

        NanoPubWriter(PrintStream out) {
            this.out = out;
        }

        @Override
        public void write(SpecimenTaxonOnly source, InteractType type, SpecimenTaxonOnly target, Study study, Dataset dataset, Stream<String> datasetInfo) {
            String nanoPubId = idGenerator.generate();
            String pubHeader = "@prefix np: <http://www.nanopub.org/nschema#> ." +
                    "@prefix dcterms: <http://purl.org/dc/terms/> ." +
                    "@prefix opm: <http://purl.org/net/opmv/ns#> ." +
                    "@prefix pav: <http://swan.mindinformatics.org/ontologies/1.2/pav/> ." +
                    "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> ." +
                    "@prefix sio: <http://semanticscience.org/resource/> ." +
                    "@prefix xsd: <http://www.w3.org/2001/XMLSchema#> ." +
                    "@prefix obo: <http://purl.obolibrary.org/obo/> ." +
                    "@prefix : <http://purl.org/nanopub/temp/NanoPub_" + nanoPubId + "#> ." +
                    "\n" +
                    ":Head {" +
                    "  : a np:Nanopublication ;" +
                    "    np:hasAssertion :Assertion ;" +
                    "    np:hasProvenance :Provenance ;" +
                    "    np:hasPublicationInfo :Pubinfo ." +
                    "}\n";

            StringBuilder builder = new StringBuilder();

            String pubBody =
                    " \n" +
                            ":Assertion {" +
                            "  :Interaction a obo:GO_0044419 ;" +
                            "    obo:RO_0000057 :Organism_1 ;" +
                            "    obo:RO_0000057 :Organism_2 .";
            builder.append(pubHeader);
            builder.append(pubBody);

            builder.append("  :Organism_1 <").append(type.getIRI())
                    .append("> :Organism_2 .\n");

            appendOrganismForTaxon(builder, "1", source.taxon);
            appendOrganismForTaxon(builder, "2", target.taxon);

            String eltonURI = "https://github.com/globalbioticinteractions/elton";
            String version = Version.getVersion();
            if (version.matches("[0-9]+\\.[0-9]+\\.[0-9]+")) {
                eltonURI += "/releases/tag/" + version;
            }

            builder.append("}" +
                    " " +
                    ":Provenance {" +
                    "  :Assertion opm:wasDerivedFrom <" + dataset.getArchiveURI() + "> ;" +
                    "    opm:wasGeneratedBy <" + eltonURI + "> ." +
                    "}" +
                    " " +
                    ":Pubinfo {" +
                    "  : pav:authoredBy <https://orcid.org/0000-0003-3138-4118> ." +
                    "  : pav:createdBy <" + eltonURI + "> ;" +
                    "    dcterms:created \"" + ISODateTimeFormat.dateTimeNoMillis().withZone(DateTimeZone.UTC).print(getDate().getTime()) + "\"^^xsd:dateTime ." +
                    "}");
            out.println(builder.toString().replace("\n", " "));
        }

        private void appendOrganismForTaxon(StringBuilder builder, String number, Taxon taxon) {
            String s = ExternalIdUtil.urlForExternalId(taxon.getExternalId());
            if (StringUtils.isNotBlank(s)) {
                builder.append("  :Organism_").append(number).append(" a <").append(s).append(">   .\n");
            }
            String name = StringEscapeUtils.escapeXml(taxon.getName());
            if (StringUtils.isNotBlank(name)) {
                builder.append("  :Organism_").append(number).append(" rdfs:label \"").append(name).append("\" .\n");
            }
        }

    }

    @Override
    public void run() {
        run(System.out);
    }

    InteractionWriter createSerializer(PrintStream out) {
        return new NanoPubWriter(out);
    }

    void run(PrintStream out) {
        DatasetFinderLocal finder = CmdUtil.getDatasetFinderLocal(getCacheDir());

        InteractionWriter serializer = createSerializer(out);

        NodeFactoryNull nodeFactory = new NodeFactoryNull() {
            Dataset dataset;
            List<String> datasetInfo;

            @Override
            public Dataset getOrCreateDataset(Dataset dataset) {
                this.dataset = dataset;
                this.datasetInfo = CmdUtil.datasetInfo(dataset);
                return super.getOrCreateDataset(dataset);
            }

            @Override
            public Specimen createSpecimen(Interaction interaction, Taxon taxon) throws NodeFactoryException {
                return new SpecimenTaxonOnly(dataset, datasetInfo.stream(), interaction.getStudy(), serializer, taxon);
            }

            @Override
            public Specimen createSpecimen(Study study, Taxon taxon) throws NodeFactoryException {
                return new SpecimenTaxonOnly(dataset, datasetInfo.stream(), study, serializer, taxon);
            }
        };

        try {
            CmdUtil.handleNamespaces(finder, namespace -> {
                String msg = "scanning for interactions in [" + namespace + "]...";
                LOG.info(msg);
                Dataset dataset = DatasetFactory.datasetFor(namespace, finder);
                nodeFactory.getOrCreateDataset(dataset);
                new GitHubImporterFactory()
                        .createImporter(dataset, nodeFactory)
                        .importStudy();
                LOG.info(msg + "done.");
            }, getNamespaces());
        } catch (DatasetFinderException e) {
            throw new RuntimeException("failed to complete name scan", e);
        }
    }
}


