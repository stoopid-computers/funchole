package com.funchole.backend.controlplane.mcp;

import java.util.List;

/**
 * Real, known-working example source for {@link FunctionExampleMcpTools}, one constant set per
 * scenario. Each constant here is the single source of truth for its scenario - it is asserted
 * byte-for-byte (or, for {@code NODE_BASIC_SOURCE}, referenced directly in place of its own
 * inline copy) by a real, currently-passing test, so this content cannot silently drift out of
 * sync with actual runtime behavior without a test also failing:
 *
 * <ul>
 *   <li>{@code NODE_BASIC_SOURCE} - {@code FlowVersionInvocationIntegrationTests#invokesADraftFlowVersionWithoutRequiringAdoption}</li>
 *   <li>{@code NODE_DATABASE_SOURCE} - {@code FunctionExampleFixturesE2ETests#nodeDatabaseExampleReadsAndWritesARealAttachedDatabase}</li>
 *   <li>{@code NODE_ENV_VARS_SOURCE} - {@code NodeEnvVarsExampleE2ETest#nodeEnvVarsExampleReadsARealFunctionVersionEnvVar}</li>
 *   <li>{@code staticMultipageFiles()} - {@code FunctionExampleFixturesIntegrationTests#staticMultipageExampleRoundTripsThroughSubmitAndRead}</li>
 * </ul>
 */
public final class FunctionExampleFixtures {

    private FunctionExampleFixtures() {
    }

    public record ExampleFile(String path, String content) {
    }

    public static final String NODE_BASIC_ENTRYPOINT = "index.mjs";
    public static final String NODE_BASIC_HANDLER = "handler";
    public static final String NODE_BASIC_SOURCE =
            "export async function handler(input) { return { status: 200, body: { ok: true, input } }; }\n";

    public static final String NODE_DATABASE_ENTRYPOINT = "index.mjs";
    public static final String NODE_DATABASE_HANDLER = "handler";
    public static final String NODE_DATABASE_SOURCE = """
            export async function handler(input, context) {
              const pool = context.db('primary');
              await pool.query('CREATE TABLE IF NOT EXISTS notes (id SERIAL PRIMARY KEY, text TEXT NOT NULL)');
              const inserted = await pool.query(
                'INSERT INTO notes (text) VALUES ($1) RETURNING id, text', [input.text]);
              const { rows } = await pool.query('SELECT id, text FROM notes ORDER BY id');
              return { status: 200, body: { inserted: inserted.rows[0], all: rows } };
            }
            """;

    public static final String NODE_ENV_VARS_ENTRYPOINT = "index.mjs";
    public static final String NODE_ENV_VARS_HANDLER = "handler";
    public static final String NODE_ENV_VARS_SOURCE = """
            export async function handler(input) {
              const greeting = process.env.GREETING;
              if (!greeting) {
                return { status: 500, body: { error: "GREETING env var is not set" } };
              }
              return { status: 200, body: { greeting } };
            }
            """;

    public static final String STATIC_ENTRYPOINT = "package.json";
    public static final String STATIC_PACKAGE_JSON = """
            {
              "name": "example-static-site",
              "private": true,
              "scripts": {
                "build": "mkdir -p dist/blog && cp index.html about.html dist/ && cp blog/index.html blog/first-post.html dist/blog/"
              }
            }
            """;
    public static final String STATIC_INDEX_HTML = """
            <!doctype html>
            <html><head><title>Home</title></head>
            <body><h1>Home</h1><p><a href="about.html">About</a> | <a href="blog/index.html">Blog</a></p></body></html>
            """;
    public static final String STATIC_ABOUT_HTML = """
            <!doctype html>
            <html><head><title>About</title></head>
            <body><h1>About</h1><p><a href="index.html">Home</a></p></body></html>
            """;
    public static final String STATIC_BLOG_INDEX_HTML = """
            <!doctype html>
            <html><head><title>Blog</title></head>
            <body><h1>Blog</h1><p><a href="first-post.html">First post</a></p></body></html>
            """;
    public static final String STATIC_BLOG_FIRST_POST_HTML = """
            <!doctype html>
            <html><head><title>First post</title></head>
            <body><h1>First post</h1><p><a href="index.html">Blog home</a></p></body></html>
            """;

    public static List<ExampleFile> staticMultipageFiles() {
        return List.of(
                new ExampleFile("package.json", STATIC_PACKAGE_JSON),
                new ExampleFile("index.html", STATIC_INDEX_HTML),
                new ExampleFile("about.html", STATIC_ABOUT_HTML),
                new ExampleFile("blog/index.html", STATIC_BLOG_INDEX_HTML),
                new ExampleFile("blog/first-post.html", STATIC_BLOG_FIRST_POST_HTML)
        );
    }
}
