--
-- PostgreSQL database dump
--

\restrict dbO0bYmpCmhiMT7XahLDeI1ve6MM5hntsi1Ax4aj1MD32tgSJKOP33UW8hG24bD

-- Dumped from database version 18.6
-- Dumped by pg_dump version 18.6

-- Started on 2026-11-07 13:17:08

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- TOC entry 2 (class 3079 OID 16581)
-- Name: postgis; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS postgis WITH SCHEMA public;


--
-- TOC entry 6542 (class 0 OID 0)
-- Dependencies: 2
-- Name: EXTENSION postgis; Type: COMMENT; Schema: -; Owner: 
--

COMMENT ON EXTENSION postgis IS 'PostGIS geometry and geography spatial types and functions';


--
-- TOC entry 3 (class 3079 OID 18663)
-- Name: pgrouting; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS pgrouting WITH SCHEMA public;


--
-- TOC entry 6543 (class 0 OID 0)
-- Dependencies: 3
-- Name: EXTENSION pgrouting; Type: COMMENT; Schema: -; Owner: 
--

COMMENT ON EXTENSION pgrouting IS 'pgRouting Extension';


--
-- TOC entry 787 (class 1255 OID 19002)
-- Name: find_safe_route(double precision, double precision, double precision, double precision, double precision); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE FUNCTION public.find_safe_route(start_lat double precision, start_lon double precision, end_lat double precision, end_lon double precision, cell_size double precision DEFAULT 0.05) RETURNS TABLE(seq integer, lat double precision, lon double precision)
    LANGUAGE plpgsql
    AS $$
DECLARE
    min_lon float := least(start_lon, end_lon) - 0.1;
    max_lon float := greatest(start_lon, end_lon) + 0.1;
    min_lat float := least(start_lat, end_lat) - 0.1;
    max_lat float := greatest(start_lat, end_lat) + 0.1;
BEGIN
    CREATE TEMP TABLE grid_vertices ON COMMIT DROP AS
    SELECT row_number() OVER () AS id, glon::float AS glon, glat::float AS glat,
           ST_SetSRID(ST_MakePoint(glon::float, glat::float), 4326) AS geom
    FROM generate_series(min_lon::numeric, max_lon::numeric, cell_size::numeric) AS glon,
         generate_series(min_lat::numeric, max_lat::numeric, cell_size::numeric) AS glat;

    CREATE TEMP TABLE grid_edges ON COMMIT DROP AS
    SELECT row_number() OVER () AS id, a.id AS source, b.id AS target,
           ST_Distance(a.geom::geography, b.geom::geography) AS cost,
           ST_Distance(a.geom::geography, b.geom::geography) AS reverse_cost,
           a.glon AS x1, a.glat AS y1, b.glon AS x2, b.glat AS y2
    FROM grid_vertices a
    JOIN grid_vertices b ON
        abs(a.glon - b.glon) <= cell_size * 1.5 AND
        abs(a.glat - b.glat) <= cell_size * 1.5 AND
        a.id != b.id
    WHERE NOT EXISTS (
        SELECT 1 FROM hazard_unified h
        WHERE ST_Intersects(h.geom, ST_MakeLine(a.geom, b.geom))
          AND h.hazard_type NOT IN ('SVA ADVISORY')
    );

    RETURN QUERY
    WITH start_v AS (
        SELECT id FROM grid_vertices
        ORDER BY geom <-> ST_SetSRID(ST_MakePoint(start_lon, start_lat), 4326) LIMIT 1
    ), end_v AS (
        SELECT id FROM grid_vertices
        ORDER BY geom <-> ST_SetSRID(ST_MakePoint(end_lon, end_lat), 4326) LIMIT 1
    )
    SELECT r.seq, v.glat, v.glon
    FROM pgr_astar(
        'SELECT id, source, target, cost, reverse_cost, x1, y1, x2, y2 FROM grid_edges',
        (SELECT id FROM start_v), (SELECT id FROM end_v)
    ) r
    JOIN grid_vertices v ON v.id = r.node;
END;
$$;


ALTER FUNCTION public.find_safe_route(start_lat double precision, start_lon double precision, end_lat double precision, end_lon double precision, cell_size double precision) OWNER TO postgres;

SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- TOC entry 270 (class 1259 OID 18318)
-- Name: climatology_baseline; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.climatology_baseline (
    region_id text NOT NULL,
    region_name text NOT NULL,
    month integer NOT NULL,
    avg_sst numeric(4,2),
    sst_stddev numeric(4,2),
    avg_chlorophyll numeric(5,3),
    chl_stddev numeric(5,3),
    source_note text,
    CONSTRAINT climatology_baseline_month_check CHECK (((month >= 1) AND (month <= 12)))
);


ALTER TABLE public.climatology_baseline OWNER TO postgres;

--
-- TOC entry 236 (class 1259 OID 16390)
-- Name: fetch_log; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.fetch_log (
    id integer NOT NULL,
    source_name text NOT NULL,
    status text NOT NULL,
    message text,
    fetched_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT fetch_log_status_check CHECK ((status = ANY (ARRAY['OK'::text, 'FAILED'::text])))
);


ALTER TABLE public.fetch_log OWNER TO postgres;

--
-- TOC entry 235 (class 1259 OID 16389)
-- Name: fetch_log_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

CREATE SEQUENCE public.fetch_log_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.fetch_log_id_seq OWNER TO postgres;

--
-- TOC entry 6544 (class 0 OID 0)
-- Dependencies: 235
-- Name: fetch_log_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: postgres
--

ALTER SEQUENCE public.fetch_log_id_seq OWNED BY public.fetch_log.id;


--
-- TOC entry 246 (class 1259 OID 16484)
-- Name: fishermen_warnings; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.fishermen_warnings (
    id integer NOT NULL,
    region text NOT NULL,
    authority text,
    detail_message text,
    warning_level text,
    message text,
    warning_time timestamp without time zone,
    latitude double precision,
    longitude double precision,
    bulletin_file text,
    fetched_at timestamp with time zone DEFAULT now() NOT NULL,
    geom public.geometry(Point,4326)
);


ALTER TABLE public.fishermen_warnings OWNER TO postgres;

--
-- TOC entry 245 (class 1259 OID 16483)
-- Name: fishermen_warnings_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

CREATE SEQUENCE public.fishermen_warnings_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.fishermen_warnings_id_seq OWNER TO postgres;

--
-- TOC entry 6545 (class 0 OID 0)
-- Dependencies: 245
-- Name: fishermen_warnings_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: postgres
--

ALTER SEQUENCE public.fishermen_warnings_id_seq OWNED BY public.fishermen_warnings.id;


--
-- TOC entry 272 (class 1259 OID 18340)
-- Name: geocoded_places; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.geocoded_places (
    place_name text NOT NULL,
    resolved_name text,
    latitude double precision,
    longitude double precision,
    geom public.geometry(Point,4326),
    fetched_at timestamp with time zone DEFAULT now()
);


ALTER TABLE public.geocoded_places OWNER TO postgres;

--
-- TOC entry 278 (class 1259 OID 18383)
-- Name: india_eez_polygon; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.india_eez_polygon (
    id integer NOT NULL,
    mrgid integer NOT NULL,
    geoname text,
    territory1 text,
    sovereign1 text,
    area_km2 double precision,
    geom public.geometry(MultiPolygon,4326),
    fetched_at timestamp without time zone
);


ALTER TABLE public.india_eez_polygon OWNER TO postgres;

--
-- TOC entry 283 (class 1259 OID 18581)
-- Name: hazard_eez_boundary; Type: MATERIALIZED VIEW; Schema: public; Owner: postgres
--

CREATE MATERIALIZED VIEW public.hazard_eez_boundary AS
 SELECT 'EEZ BOUNDARY'::text AS hazard_type,
    'CAUTION'::text AS risk_level,
    'Approaching international maritime boundary (India EEZ limit)'::text AS message,
    'India EEZ'::text AS region,
    'Indian Coast Guard / INCOIS'::text AS issued_by,
    'https://marineregions.org/gazetteer.php?p=details&id=8480'::text AS source,
    (public.st_buffer((public.st_boundary(geom))::public.geography, (5000)::double precision))::public.geometry AS geom,
    (fetched_at)::timestamp with time zone AS fetched_at,
    (fetched_at)::timestamp with time zone AS issued_at
   FROM public.india_eez_polygon
  WITH NO DATA;


ALTER MATERIALIZED VIEW public.hazard_eez_boundary OWNER TO postgres;

--
-- TOC entry 280 (class 1259 OID 18534)
-- Name: hazard_fishermen; Type: VIEW; Schema: public; Owner: postgres
--

CREATE VIEW public.hazard_fishermen AS
 SELECT 'FISHERMEN WARNING'::text AS hazard_type,
    warning_level AS risk_level,
    concat_ws(' - '::text, NULLIF(detail_message, 'NIL'::text), NULLIF(message, 'NIL'::text)) AS message,
    region,
    authority AS issued_by,
    bulletin_file AS source,
    (public.st_buffer((public.st_setsrid(public.st_makepoint(longitude, latitude), 4326))::public.geography, (25000)::double precision))::public.geometry AS geom,
    fetched_at,
    warning_time AS issued_at
   FROM public.fishermen_warnings
  WHERE ((message IS NOT NULL) AND (message !~~* '%no warning%'::text) AND (latitude IS NOT NULL));


ALTER VIEW public.hazard_fishermen OWNER TO postgres;

--
-- TOC entry 269 (class 1259 OID 18101)
-- Name: marine_observations; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.marine_observations (
    id integer NOT NULL,
    station_id text NOT NULL,
    station_type text NOT NULL,
    station_name text,
    observed_at timestamp without time zone,
    latitude double precision,
    longitude double precision,
    pressure_hpa double precision,
    air_temp_c double precision,
    dew_point_c double precision,
    humidity_pct double precision,
    wind_direction text,
    wind_speed double precision,
    wind_speed_unit text,
    wind_gust double precision,
    visibility_m double precision,
    sky_condition text,
    sst_c double precision,
    wave_direction text,
    wave_period_sec double precision,
    sig_wave_height_m double precision,
    max_wave_height_m double precision,
    raw_fields jsonb,
    fetched_at timestamp with time zone DEFAULT now() NOT NULL
);


ALTER TABLE public.marine_observations OWNER TO postgres;

--
-- TOC entry 282 (class 1259 OID 18558)
-- Name: hazard_observations; Type: VIEW; Schema: public; Owner: postgres
--

CREATE VIEW public.hazard_observations AS
 SELECT 'HIGH_WAVE_OR_WIND'::text AS hazard_type,
        CASE
            WHEN ((marine_observations.sig_wave_height_m >= (4.5)::double precision) OR ((marine_observations.wind_speed_unit = 'KT'::text) AND (marine_observations.wind_speed >= (35)::double precision)) OR ((marine_observations.wind_speed_unit = 'Km/Hr'::text) AND (marine_observations.wind_speed >= (65)::double precision))) THEN 'SEVERE'::text
            ELSE 'HIGH'::text
        END AS risk_level,
    concat_ws(', '::text,
        CASE
            WHEN (marine_observations.sig_wave_height_m IS NOT NULL) THEN ('wave_height_m='::text || marine_observations.sig_wave_height_m)
            ELSE NULL::text
        END,
        CASE
            WHEN (marine_observations.wave_period_sec IS NOT NULL) THEN ('wave_period_sec='::text || marine_observations.wave_period_sec)
            ELSE NULL::text
        END,
        CASE
            WHEN (marine_observations.wind_speed IS NOT NULL) THEN ((('wind_speed='::text || marine_observations.wind_speed) || ' '::text) || marine_observations.wind_speed_unit)
            ELSE NULL::text
        END,
        CASE
            WHEN (marine_observations.wind_gust IS NOT NULL) THEN ('wind_gust='::text || marine_observations.wind_gust)
            ELSE NULL::text
        END) AS message,
    concat_ws(' - '::text,
        CASE marine_observations.station_type
            WHEN 'buoy_obs'::text THEN 'Buoy'::text
            WHEN 'coastal_stn_obs'::text THEN 'Coastal Station'::text
            WHEN 'coastal_ship_obs'::text THEN 'Ship'::text
            ELSE marine_observations.station_type
        END, marine_observations.station_name) AS region,
    'India Meteorological Department'::text AS issued_by,
    'https://mausam.imd.gov.in/responsive/coastal_observations.php'::text AS source,
    (public.st_buffer((public.st_setsrid(public.st_makepoint(marine_observations.longitude, marine_observations.latitude), 4326))::public.geography, (15000)::double precision))::public.geometry AS geom,
    marine_observations.fetched_at,
    marine_observations.observed_at AS issued_at
   FROM public.marine_observations
  WHERE ((marine_observations.sig_wave_height_m >= (3.0)::double precision) OR ((marine_observations.wind_speed_unit = 'KT'::text) AND (marine_observations.wind_speed >= (25.0)::double precision)) OR ((marine_observations.wind_speed_unit = 'Km/Hr'::text) AND (marine_observations.wind_speed >= (46.3)::double precision)))
UNION ALL
 SELECT 'LOW_VISIBILITY'::text AS hazard_type,
        CASE
            WHEN (marine_observations.visibility_m < (1000)::double precision) THEN 'SEVERE'::text
            ELSE 'HIGH'::text
        END AS risk_level,
    concat_ws(', '::text, ('visibility_m='::text || marine_observations.visibility_m),
        CASE
            WHEN (marine_observations.sky_condition IS NOT NULL) THEN ('sky_condition='::text || marine_observations.sky_condition)
            ELSE NULL::text
        END) AS message,
    concat_ws(' - '::text,
        CASE marine_observations.station_type
            WHEN 'buoy_obs'::text THEN 'Buoy'::text
            WHEN 'coastal_stn_obs'::text THEN 'Coastal Station'::text
            WHEN 'coastal_ship_obs'::text THEN 'Ship'::text
            ELSE marine_observations.station_type
        END, marine_observations.station_name) AS region,
    'India Meteorological Department'::text AS issued_by,
    'https://mausam.imd.gov.in/responsive/coastal_observations.php'::text AS source,
    (public.st_buffer((public.st_setsrid(public.st_makepoint(marine_observations.longitude, marine_observations.latitude), 4326))::public.geography, (15000)::double precision))::public.geometry AS geom,
    marine_observations.fetched_at,
    marine_observations.observed_at AS issued_at
   FROM public.marine_observations
  WHERE ((marine_observations.visibility_m IS NOT NULL) AND (marine_observations.visibility_m < (2000)::double precision))
UNION ALL
 SELECT 'ROUGH_SEA_STATE'::text AS hazard_type,
    'HIGH'::text AS risk_level,
    concat_ws(', '::text, ('wave_height_m='::text || marine_observations.sig_wave_height_m), ('wave_period_sec='::text || marine_observations.wave_period_sec)) AS message,
    concat_ws(' - '::text,
        CASE marine_observations.station_type
            WHEN 'buoy_obs'::text THEN 'Buoy'::text
            WHEN 'coastal_stn_obs'::text THEN 'Coastal Station'::text
            WHEN 'coastal_ship_obs'::text THEN 'Ship'::text
            ELSE marine_observations.station_type
        END, marine_observations.station_name) AS region,
    'India Meteorological Department'::text AS issued_by,
    'https://mausam.imd.gov.in/responsive/coastal_observations.php'::text AS source,
    (public.st_buffer((public.st_setsrid(public.st_makepoint(marine_observations.longitude, marine_observations.latitude), 4326))::public.geography, (15000)::double precision))::public.geometry AS geom,
    marine_observations.fetched_at,
    marine_observations.observed_at AS issued_at
   FROM public.marine_observations
  WHERE ((marine_observations.sig_wave_height_m IS NOT NULL) AND (marine_observations.wave_period_sec IS NOT NULL) AND (marine_observations.sig_wave_height_m >= (2.0)::double precision) AND (marine_observations.wave_period_sec <= (6.0)::double precision));


ALTER VIEW public.hazard_observations OWNER TO postgres;

--
-- TOC entry 238 (class 1259 OID 16424)
-- Name: port_warnings; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.port_warnings (
    id integer NOT NULL,
    port_name text NOT NULL,
    warning_level text,
    latitude double precision,
    longitude double precision,
    issued_at timestamp with time zone,
    message text,
    fetched_at timestamp with time zone DEFAULT now() NOT NULL,
    highlighted boolean,
    bulletin_file text,
    geom public.geometry(Point,4326)
);


ALTER TABLE public.port_warnings OWNER TO postgres;

--
-- TOC entry 279 (class 1259 OID 18530)
-- Name: hazard_ports; Type: VIEW; Schema: public; Owner: postgres
--

CREATE VIEW public.hazard_ports AS
 SELECT 'PORT WARNING'::text AS hazard_type,
    warning_level AS risk_level,
    message,
    port_name AS region,
    'India Meteorological Department'::text AS issued_by,
    bulletin_file AS source,
    (public.st_buffer((public.st_setsrid(public.st_makepoint(longitude, latitude), 4326))::public.geography, (10000)::double precision))::public.geometry AS geom,
    fetched_at,
    issued_at
   FROM public.port_warnings
  WHERE (highlighted = true);


ALTER VIEW public.hazard_ports OWNER TO postgres;

--
-- TOC entry 258 (class 1259 OID 17679)
-- Name: svas_advisory_status; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.svas_advisory_status (
    id integer NOT NULL,
    district_id integer,
    boat_width integer,
    forecast_day integer,
    forecast_date date,
    status_color text,
    fetched_at timestamp with time zone DEFAULT now()
);


ALTER TABLE public.svas_advisory_status OWNER TO postgres;

--
-- TOC entry 259 (class 1259 OID 17696)
-- Name: svas_advisory_text; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.svas_advisory_text (
    advisory_status_id integer NOT NULL,
    language_code text NOT NULL,
    advisory_text text
);


ALTER TABLE public.svas_advisory_text OWNER TO postgres;

--
-- TOC entry 256 (class 1259 OID 17669)
-- Name: svas_districts; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.svas_districts (
    fid_coasta integer NOT NULL,
    district_name text,
    state text,
    geom public.geometry(MultiPolygon,4326)
);


ALTER TABLE public.svas_districts OWNER TO postgres;

--
-- TOC entry 281 (class 1259 OID 18540)
-- Name: hazard_sva; Type: VIEW; Schema: public; Owner: postgres
--

CREATE VIEW public.hazard_sva AS
 WITH daily_advisories AS (
         SELECT s.district_id,
            s.forecast_day,
            max(s.forecast_date) AS forecast_date,
            bool_or((lower(s.status_color) = 'alert'::text)) AS day_has_alert,
            jsonb_agg(jsonb_build_object('boat_width', s.boat_width, 'status', s.status_color, 'text', regexp_replace(COALESCE(t.advisory_text, s.status_color), '(&emsp;|emsp|EMSPEM|\u2003)+'::text, ' '::text, 'g'::text)) ORDER BY s.boat_width) AS width_advisories,
            max(s.fetched_at) AS fetched_at
           FROM (public.svas_advisory_status s
             LEFT JOIN public.svas_advisory_text t ON (((s.id = t.advisory_status_id) AND (t.language_code = 'ENG'::text))))
          GROUP BY s.district_id, s.forecast_day
        )
 SELECT 'SVA ADVISORY'::text AS hazard_type,
    'alert'::text AS risk_level,
    (jsonb_agg(jsonb_build_object('forecast_date', da.forecast_date, 'advisories', da.width_advisories) ORDER BY da.forecast_date))::text AS message,
    concat_ws(' - '::text, NULLIF(d.district_name, 'NIL'::text), NULLIF(d.state, 'NIL'::text)) AS region,
    'INCOIS'::text AS issued_by,
    'https://incois.gov.in/site/services/SVA_overview.jsp'::text AS source,
    d.geom,
    max(da.fetched_at) AS fetched_at,
    min(da.forecast_date) AS issued_at
   FROM (daily_advisories da
     JOIN public.svas_districts d ON ((da.district_id = d.fid_coasta)))
  GROUP BY d.fid_coasta, d.geom, d.district_name, d.state
 HAVING (bool_or(da.day_has_alert) = true);


ALTER VIEW public.hazard_sva OWNER TO postgres;

--
-- TOC entry 284 (class 1259 OID 18602)
-- Name: hazard_unified; Type: VIEW; Schema: public; Owner: postgres
--

CREATE VIEW public.hazard_unified AS
 SELECT hazard_ports.hazard_type,
    hazard_ports.risk_level,
    hazard_ports.message,
    hazard_ports.region,
    hazard_ports.issued_by,
    hazard_ports.source,
    hazard_ports.geom,
    hazard_ports.fetched_at,
    hazard_ports.issued_at
   FROM public.hazard_ports
UNION ALL
 SELECT hazard_fishermen.hazard_type,
    hazard_fishermen.risk_level,
    hazard_fishermen.message,
    hazard_fishermen.region,
    hazard_fishermen.issued_by,
    hazard_fishermen.source,
    hazard_fishermen.geom,
    hazard_fishermen.fetched_at,
    hazard_fishermen.issued_at
   FROM public.hazard_fishermen
UNION ALL
 SELECT hazard_observations.hazard_type,
    hazard_observations.risk_level,
    hazard_observations.message,
    hazard_observations.region,
    hazard_observations.issued_by,
    hazard_observations.source,
    hazard_observations.geom,
    hazard_observations.fetched_at,
    hazard_observations.issued_at
   FROM public.hazard_observations
UNION ALL
 SELECT hazard_sva.hazard_type,
    hazard_sva.risk_level,
    hazard_sva.message,
    hazard_sva.region,
    hazard_sva.issued_by,
    hazard_sva.source,
    hazard_sva.geom,
    hazard_sva.fetched_at,
    hazard_sva.issued_at
   FROM public.hazard_sva
UNION ALL
 SELECT hazard_eez_boundary.hazard_type,
    hazard_eez_boundary.risk_level,
    hazard_eez_boundary.message,
    hazard_eez_boundary.region,
    hazard_eez_boundary.issued_by,
    hazard_eez_boundary.source,
    hazard_eez_boundary.geom,
    hazard_eez_boundary.fetched_at,
    hazard_eez_boundary.issued_at
   FROM public.hazard_eez_boundary;


ALTER VIEW public.hazard_unified OWNER TO postgres;

--
-- TOC entry 263 (class 1259 OID 17829)
-- Name: india_eez; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.india_eez (
    id integer NOT NULL,
    feature_id text NOT NULL,
    left_fid integer,
    right_fid integer,
    geom public.geometry(MultiLineString,4326),
    fetched_at timestamp with time zone DEFAULT now() NOT NULL
);


ALTER TABLE public.india_eez OWNER TO postgres;

--
-- TOC entry 262 (class 1259 OID 17828)
-- Name: india_eez_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

CREATE SEQUENCE public.india_eez_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.india_eez_id_seq OWNER TO postgres;

--
-- TOC entry 6546 (class 0 OID 0)
-- Dependencies: 262
-- Name: india_eez_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: postgres
--

ALTER SEQUENCE public.india_eez_id_seq OWNED BY public.india_eez.id;


--
-- TOC entry 277 (class 1259 OID 18382)
-- Name: india_eez_polygon_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

CREATE SEQUENCE public.india_eez_polygon_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.india_eez_polygon_id_seq OWNER TO postgres;

--
-- TOC entry 6547 (class 0 OID 0)
-- Dependencies: 277
-- Name: india_eez_polygon_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: postgres
--

ALTER SEQUENCE public.india_eez_polygon_id_seq OWNED BY public.india_eez_polygon.id;


--
-- TOC entry 265 (class 1259 OID 18067)
-- Name: landing_centers; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.landing_centers (
    id integer NOT NULL,
    lc_unique_id character varying(50) NOT NULL,
    object_id integer,
    lc_name character varying(150),
    sector_name character varying(100),
    sector_id character varying(20),
    district_name character varying(100),
    forecast_id integer,
    direction character varying(10),
    bearing integer,
    depth_from integer,
    depth_to integer,
    updated_at timestamp with time zone,
    validity_date timestamp with time zone,
    geom public.geometry(Point,4326),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);


ALTER TABLE public.landing_centers OWNER TO postgres;

--
-- TOC entry 264 (class 1259 OID 18066)
-- Name: landing_centers_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

CREATE SEQUENCE public.landing_centers_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.landing_centers_id_seq OWNER TO postgres;

--
-- TOC entry 6548 (class 0 OID 0)
-- Dependencies: 264
-- Name: landing_centers_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: postgres
--

ALTER SEQUENCE public.landing_centers_id_seq OWNED BY public.landing_centers.id;


--
-- TOC entry 242 (class 1259 OID 16452)
-- Name: lightning_alerts; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.lightning_alerts (
    id integer NOT NULL,
    latitude double precision NOT NULL,
    longitude double precision NOT NULL,
    strike_time timestamp with time zone,
    source text,
    fetched_at timestamp with time zone DEFAULT now() NOT NULL
);


ALTER TABLE public.lightning_alerts OWNER TO postgres;

--
-- TOC entry 241 (class 1259 OID 16451)
-- Name: lightning_alerts_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

CREATE SEQUENCE public.lightning_alerts_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.lightning_alerts_id_seq OWNER TO postgres;

--
-- TOC entry 6549 (class 0 OID 0)
-- Dependencies: 241
-- Name: lightning_alerts_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: postgres
--

ALTER SEQUENCE public.lightning_alerts_id_seq OWNED BY public.lightning_alerts.id;


--
-- TOC entry 248 (class 1259 OID 16499)
-- Name: location_lookup; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.location_lookup (
    id integer NOT NULL,
    region_name text NOT NULL,
    latitude double precision NOT NULL,
    longitude double precision NOT NULL,
    geom public.geometry(Point,4326)
);


ALTER TABLE public.location_lookup OWNER TO postgres;

--
-- TOC entry 247 (class 1259 OID 16498)
-- Name: location_lookup_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

CREATE SEQUENCE public.location_lookup_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.location_lookup_id_seq OWNER TO postgres;

--
-- TOC entry 6550 (class 0 OID 0)
-- Dependencies: 247
-- Name: location_lookup_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: postgres
--

ALTER SEQUENCE public.location_lookup_id_seq OWNED BY public.location_lookup.id;


--
-- TOC entry 268 (class 1259 OID 18100)
-- Name: marine_observations_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

CREATE SEQUENCE public.marine_observations_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.marine_observations_id_seq OWNER TO postgres;

--
-- TOC entry 6551 (class 0 OID 0)
-- Dependencies: 268
-- Name: marine_observations_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: postgres
--

ALTER SEQUENCE public.marine_observations_id_seq OWNED BY public.marine_observations.id;


--
-- TOC entry 244 (class 1259 OID 16467)
-- Name: ocean_point_readings; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.ocean_point_readings (
    id integer NOT NULL,
    variable text NOT NULL,
    latitude double precision NOT NULL,
    longitude double precision NOT NULL,
    value double precision,
    data_date date NOT NULL,
    source_tier text,
    fetched_at timestamp with time zone DEFAULT now() NOT NULL,
    tile_name text
);


ALTER TABLE public.ocean_point_readings OWNER TO postgres;

--
-- TOC entry 243 (class 1259 OID 16466)
-- Name: ocean_point_readings_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

CREATE SEQUENCE public.ocean_point_readings_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.ocean_point_readings_id_seq OWNER TO postgres;

--
-- TOC entry 6552 (class 0 OID 0)
-- Dependencies: 243
-- Name: ocean_point_readings_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: postgres
--

ALTER SEQUENCE public.ocean_point_readings_id_seq OWNED BY public.ocean_point_readings.id;


--
-- TOC entry 267 (class 1259 OID 18085)
-- Name: pfz_eez; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.pfz_eez (
    id integer NOT NULL,
    feature_id character varying(50) NOT NULL,
    left_fid integer,
    right_fid integer,
    geom public.geometry(MultiLineString,4326),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);


ALTER TABLE public.pfz_eez OWNER TO postgres;

--
-- TOC entry 266 (class 1259 OID 18084)
-- Name: pfz_eez_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

CREATE SEQUENCE public.pfz_eez_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.pfz_eez_id_seq OWNER TO postgres;

--
-- TOC entry 6553 (class 0 OID 0)
-- Dependencies: 266
-- Name: pfz_eez_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: postgres
--

ALTER SEQUENCE public.pfz_eez_id_seq OWNED BY public.pfz_eez.id;


--
-- TOC entry 261 (class 1259 OID 17813)
-- Name: pfz_lines; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.pfz_lines (
    id integer NOT NULL,
    feature_id text NOT NULL,
    category text,
    julian_day integer,
    year integer,
    sno text,
    uid text,
    length_km double precision,
    geom public.geometry(MultiLineString,4326),
    fetched_at timestamp with time zone DEFAULT now() NOT NULL
);


ALTER TABLE public.pfz_lines OWNER TO postgres;

--
-- TOC entry 260 (class 1259 OID 17812)
-- Name: pfz_lines_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

CREATE SEQUENCE public.pfz_lines_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.pfz_lines_id_seq OWNER TO postgres;

--
-- TOC entry 6554 (class 0 OID 0)
-- Dependencies: 260
-- Name: pfz_lines_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: postgres
--

ALTER SEQUENCE public.pfz_lines_id_seq OWNED BY public.pfz_lines.id;


--
-- TOC entry 250 (class 1259 OID 16529)
-- Name: pfz_point; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.pfz_point (
    id integer NOT NULL,
    sec_id text NOT NULL,
    location_name text NOT NULL,
    direction text,
    bearing_deg integer,
    dist_min_km real,
    dist_max_km real,
    depth_min_m real,
    depth_max_m real,
    latitude real NOT NULL,
    longitude real NOT NULL,
    fetched_at timestamp without time zone DEFAULT now(),
    geom public.geometry(Point,4326) GENERATED ALWAYS AS (public.st_setsrid(public.st_makepoint((longitude)::double precision, (latitude)::double precision), 4326)) STORED
);


ALTER TABLE public.pfz_point OWNER TO postgres;

--
-- TOC entry 249 (class 1259 OID 16528)
-- Name: pfz_point_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

CREATE SEQUENCE public.pfz_point_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.pfz_point_id_seq OWNER TO postgres;

--
-- TOC entry 6555 (class 0 OID 0)
-- Dependencies: 249
-- Name: pfz_point_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: postgres
--

ALTER SEQUENCE public.pfz_point_id_seq OWNED BY public.pfz_point.id;


--
-- TOC entry 271 (class 1259 OID 18331)
-- Name: poi_unified; Type: VIEW; Schema: public; Owner: postgres
--

CREATE VIEW public.poi_unified AS
 SELECT 'pfz'::text AS poi_type,
    pfz_point.location_name AS name,
    pfz_point.latitude,
    pfz_point.longitude,
    (public.st_setsrid(public.st_makepoint((pfz_point.longitude)::double precision, (pfz_point.latitude)::double precision), 4326))::public.geometry(Point,4326) AS geom,
    jsonb_build_object('sec_id', pfz_point.sec_id, 'direction', pfz_point.direction, 'bearing_deg', pfz_point.bearing_deg, 'dist_min_km', pfz_point.dist_min_km, 'dist_max_km', pfz_point.dist_max_km, 'depth_min_m', pfz_point.depth_min_m, 'depth_max_m', pfz_point.depth_max_m) AS metadata,
    pfz_point.fetched_at AS last_updated
   FROM public.pfz_point
UNION ALL
 SELECT 'port'::text AS poi_type,
    port_warnings.port_name AS name,
    port_warnings.latitude,
    port_warnings.longitude,
    (public.st_setsrid(public.st_makepoint(port_warnings.longitude, port_warnings.latitude), 4326))::public.geometry(Point,4326) AS geom,
    jsonb_build_object('warning_level', port_warnings.warning_level, 'message', port_warnings.message, 'highlighted', port_warnings.highlighted, 'issued_at', port_warnings.issued_at) AS metadata,
    port_warnings.fetched_at AS last_updated
   FROM public.port_warnings
UNION ALL
 SELECT 'landing_centre'::text AS poi_type,
    landing_centers.lc_name AS name,
    public.st_y(landing_centers.geom) AS latitude,
    public.st_x(landing_centers.geom) AS longitude,
    landing_centers.geom,
    jsonb_build_object('lc_unique_id', landing_centers.lc_unique_id, 'object_id', landing_centers.object_id, 'sector_name', landing_centers.sector_name, 'sector_id', landing_centers.sector_id, 'district_name', landing_centers.district_name, 'forecast_id', landing_centers.forecast_id, 'direction', landing_centers.direction, 'bearing', landing_centers.bearing, 'depth_from', landing_centers.depth_from, 'depth_to', landing_centers.depth_to) AS metadata,
    landing_centers.created_at AS last_updated
   FROM public.landing_centers;


ALTER VIEW public.poi_unified OWNER TO postgres;

--
-- TOC entry 237 (class 1259 OID 16423)
-- Name: port_warnings_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

CREATE SEQUENCE public.port_warnings_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.port_warnings_id_seq OWNER TO postgres;

--
-- TOC entry 6556 (class 0 OID 0)
-- Dependencies: 237
-- Name: port_warnings_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: postgres
--

ALTER SEQUENCE public.port_warnings_id_seq OWNED BY public.port_warnings.id;


--
-- TOC entry 257 (class 1259 OID 17678)
-- Name: svas_advisory_status_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

CREATE SEQUENCE public.svas_advisory_status_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.svas_advisory_status_id_seq OWNER TO postgres;

--
-- TOC entry 6557 (class 0 OID 0)
-- Dependencies: 257
-- Name: svas_advisory_status_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: postgres
--

ALTER SEQUENCE public.svas_advisory_status_id_seq OWNED BY public.svas_advisory_status.id;


--
-- TOC entry 240 (class 1259 OID 16437)
-- Name: tide_forecasts; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.tide_forecasts (
    id integer NOT NULL,
    station_name text NOT NULL,
    latitude double precision,
    longitude double precision,
    forecast_time timestamp with time zone NOT NULL,
    tide_height_m double precision,
    wave_height_m double precision,
    fetched_at timestamp with time zone DEFAULT now() NOT NULL
);


ALTER TABLE public.tide_forecasts OWNER TO postgres;

--
-- TOC entry 239 (class 1259 OID 16436)
-- Name: tide_forecasts_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

CREATE SEQUENCE public.tide_forecasts_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.tide_forecasts_id_seq OWNER TO postgres;

--
-- TOC entry 6558 (class 0 OID 0)
-- Dependencies: 239
-- Name: tide_forecasts_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: postgres
--

ALTER SEQUENCE public.tide_forecasts_id_seq OWNED BY public.tide_forecasts.id;


--
-- TOC entry 276 (class 1259 OID 18361)
-- Name: weather_daily_summary; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.weather_daily_summary (
    id integer NOT NULL,
    location_name text,
    latitude double precision,
    longitude double precision,
    geom public.geometry(Point,4326),
    forecast_date date,
    temp_max double precision,
    temp_min double precision,
    dominant_condition text,
    max_chance_of_rain integer,
    fetched_at timestamp with time zone DEFAULT now(),
    grid_lat double precision,
    grid_lon double precision
);


ALTER TABLE public.weather_daily_summary OWNER TO postgres;

--
-- TOC entry 275 (class 1259 OID 18360)
-- Name: weather_daily_summary_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

CREATE SEQUENCE public.weather_daily_summary_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.weather_daily_summary_id_seq OWNER TO postgres;

--
-- TOC entry 6559 (class 0 OID 0)
-- Dependencies: 275
-- Name: weather_daily_summary_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: postgres
--

ALTER SEQUENCE public.weather_daily_summary_id_seq OWNED BY public.weather_daily_summary.id;


--
-- TOC entry 274 (class 1259 OID 18350)
-- Name: weather_hourly; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.weather_hourly (
    id integer NOT NULL,
    location_name text,
    latitude double precision,
    longitude double precision,
    geom public.geometry(Point,4326),
    forecast_time timestamp with time zone,
    temp_c double precision,
    condition_text text,
    condition_code integer,
    wind_speed double precision,
    wind_direction double precision,
    humidity double precision,
    chance_of_rain integer,
    fetched_at timestamp with time zone DEFAULT now(),
    grid_lat double precision,
    grid_lon double precision
);


ALTER TABLE public.weather_hourly OWNER TO postgres;

--
-- TOC entry 273 (class 1259 OID 18349)
-- Name: weather_hourly_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

CREATE SEQUENCE public.weather_hourly_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.weather_hourly_id_seq OWNER TO postgres;

--
-- TOC entry 6560 (class 0 OID 0)
-- Dependencies: 273
-- Name: weather_hourly_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: postgres
--

ALTER SEQUENCE public.weather_hourly_id_seq OWNED BY public.weather_hourly.id;


--
-- TOC entry 6239 (class 2604 OID 16393)
-- Name: fetch_log id; Type: DEFAULT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.fetch_log ALTER COLUMN id SET DEFAULT nextval('public.fetch_log_id_seq'::regclass);


--
-- TOC entry 6249 (class 2604 OID 16487)
-- Name: fishermen_warnings id; Type: DEFAULT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.fishermen_warnings ALTER COLUMN id SET DEFAULT nextval('public.fishermen_warnings_id_seq'::regclass);


--
-- TOC entry 6259 (class 2604 OID 17832)
-- Name: india_eez id; Type: DEFAULT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.india_eez ALTER COLUMN id SET DEFAULT nextval('public.india_eez_id_seq'::regclass);


--
-- TOC entry 6273 (class 2604 OID 18386)
-- Name: india_eez_polygon id; Type: DEFAULT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.india_eez_polygon ALTER COLUMN id SET DEFAULT nextval('public.india_eez_polygon_id_seq'::regclass);


--
-- TOC entry 6261 (class 2604 OID 18070)
-- Name: landing_centers id; Type: DEFAULT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.landing_centers ALTER COLUMN id SET DEFAULT nextval('public.landing_centers_id_seq'::regclass);


--
-- TOC entry 6245 (class 2604 OID 16455)
-- Name: lightning_alerts id; Type: DEFAULT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.lightning_alerts ALTER COLUMN id SET DEFAULT nextval('public.lightning_alerts_id_seq'::regclass);


--
-- TOC entry 6251 (class 2604 OID 16502)
-- Name: location_lookup id; Type: DEFAULT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.location_lookup ALTER COLUMN id SET DEFAULT nextval('public.location_lookup_id_seq'::regclass);


--
-- TOC entry 6266 (class 2604 OID 18104)
-- Name: marine_observations id; Type: DEFAULT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.marine_observations ALTER COLUMN id SET DEFAULT nextval('public.marine_observations_id_seq'::regclass);


--
-- TOC entry 6247 (class 2604 OID 16470)
-- Name: ocean_point_readings id; Type: DEFAULT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.ocean_point_readings ALTER COLUMN id SET DEFAULT nextval('public.ocean_point_readings_id_seq'::regclass);


--
-- TOC entry 6263 (class 2604 OID 18088)
-- Name: pfz_eez id; Type: DEFAULT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.pfz_eez ALTER COLUMN id SET DEFAULT nextval('public.pfz_eez_id_seq'::regclass);


--
-- TOC entry 6257 (class 2604 OID 17816)
-- Name: pfz_lines id; Type: DEFAULT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.pfz_lines ALTER COLUMN id SET DEFAULT nextval('public.pfz_lines_id_seq'::regclass);


--
-- TOC entry 6252 (class 2604 OID 16532)
-- Name: pfz_point id; Type: DEFAULT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.pfz_point ALTER COLUMN id SET DEFAULT nextval('public.pfz_point_id_seq'::regclass);


--
-- TOC entry 6241 (class 2604 OID 16427)
-- Name: port_warnings id; Type: DEFAULT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.port_warnings ALTER COLUMN id SET DEFAULT nextval('public.port_warnings_id_seq'::regclass);


--
-- TOC entry 6255 (class 2604 OID 17682)
-- Name: svas_advisory_status id; Type: DEFAULT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.svas_advisory_status ALTER COLUMN id SET DEFAULT nextval('public.svas_advisory_status_id_seq'::regclass);


--
-- TOC entry 6243 (class 2604 OID 16440)
-- Name: tide_forecasts id; Type: DEFAULT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.tide_forecasts ALTER COLUMN id SET DEFAULT nextval('public.tide_forecasts_id_seq'::regclass);


--
-- TOC entry 6271 (class 2604 OID 18364)
-- Name: weather_daily_summary id; Type: DEFAULT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.weather_daily_summary ALTER COLUMN id SET DEFAULT nextval('public.weather_daily_summary_id_seq'::regclass);


--
-- TOC entry 6269 (class 2604 OID 18353)
-- Name: weather_hourly id; Type: DEFAULT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.weather_hourly ALTER COLUMN id SET DEFAULT nextval('public.weather_hourly_id_seq'::regclass);


--
-- TOC entry 6361 (class 2606 OID 18328)
-- Name: climatology_baseline climatology_baseline_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.climatology_baseline
    ADD CONSTRAINT climatology_baseline_pkey PRIMARY KEY (region_id, month);


--
-- TOC entry 6278 (class 2606 OID 16403)
-- Name: fetch_log fetch_log_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.fetch_log
    ADD CONSTRAINT fetch_log_pkey PRIMARY KEY (id);


--
-- TOC entry 6297 (class 2606 OID 16495)
-- Name: fishermen_warnings fishermen_warnings_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.fishermen_warnings
    ADD CONSTRAINT fishermen_warnings_pkey PRIMARY KEY (id);


--
-- TOC entry 6363 (class 2606 OID 18348)
-- Name: geocoded_places geocoded_places_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.geocoded_places
    ADD CONSTRAINT geocoded_places_pkey PRIMARY KEY (place_name);


--
-- TOC entry 6341 (class 2606 OID 17842)
-- Name: india_eez india_eez_feature_id_key; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.india_eez
    ADD CONSTRAINT india_eez_feature_id_key UNIQUE (feature_id);


--
-- TOC entry 6343 (class 2606 OID 17840)
-- Name: india_eez india_eez_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.india_eez
    ADD CONSTRAINT india_eez_pkey PRIMARY KEY (id);


--
-- TOC entry 6372 (class 2606 OID 18394)
-- Name: india_eez_polygon india_eez_polygon_mrgid_key; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.india_eez_polygon
    ADD CONSTRAINT india_eez_polygon_mrgid_key UNIQUE (mrgid);


--
-- TOC entry 6374 (class 2606 OID 18392)
-- Name: india_eez_polygon india_eez_polygon_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.india_eez_polygon
    ADD CONSTRAINT india_eez_polygon_pkey PRIMARY KEY (id);


--
-- TOC entry 6346 (class 2606 OID 18079)
-- Name: landing_centers landing_centers_lc_unique_id_key; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.landing_centers
    ADD CONSTRAINT landing_centers_lc_unique_id_key UNIQUE (lc_unique_id);


--
-- TOC entry 6348 (class 2606 OID 18077)
-- Name: landing_centers landing_centers_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.landing_centers
    ADD CONSTRAINT landing_centers_pkey PRIMARY KEY (id);


--
-- TOC entry 6290 (class 2606 OID 16464)
-- Name: lightning_alerts lightning_alerts_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.lightning_alerts
    ADD CONSTRAINT lightning_alerts_pkey PRIMARY KEY (id);


--
-- TOC entry 6305 (class 2606 OID 16510)
-- Name: location_lookup location_lookup_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.location_lookup
    ADD CONSTRAINT location_lookup_pkey PRIMARY KEY (id);


--
-- TOC entry 6307 (class 2606 OID 16512)
-- Name: location_lookup location_lookup_region_name_key; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.location_lookup
    ADD CONSTRAINT location_lookup_region_name_key UNIQUE (region_name);


--
-- TOC entry 6357 (class 2606 OID 18113)
-- Name: marine_observations marine_observations_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.marine_observations
    ADD CONSTRAINT marine_observations_pkey PRIMARY KEY (id);


--
-- TOC entry 6359 (class 2606 OID 18115)
-- Name: marine_observations marine_observations_station_id_observed_at_key; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.marine_observations
    ADD CONSTRAINT marine_observations_station_id_observed_at_key UNIQUE (station_id, observed_at);


--
-- TOC entry 6293 (class 2606 OID 16481)
-- Name: ocean_point_readings ocean_point_readings_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.ocean_point_readings
    ADD CONSTRAINT ocean_point_readings_pkey PRIMARY KEY (id);


--
-- TOC entry 6295 (class 2606 OID 17897)
-- Name: ocean_point_readings ocean_point_readings_unique; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.ocean_point_readings
    ADD CONSTRAINT ocean_point_readings_unique UNIQUE (variable, latitude, longitude, data_date);


--
-- TOC entry 6351 (class 2606 OID 18098)
-- Name: pfz_eez pfz_eez_feature_id_key; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.pfz_eez
    ADD CONSTRAINT pfz_eez_feature_id_key UNIQUE (feature_id);


--
-- TOC entry 6353 (class 2606 OID 18096)
-- Name: pfz_eez pfz_eez_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.pfz_eez
    ADD CONSTRAINT pfz_eez_pkey PRIMARY KEY (id);


--
-- TOC entry 6336 (class 2606 OID 17826)
-- Name: pfz_lines pfz_lines_feature_id_key; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.pfz_lines
    ADD CONSTRAINT pfz_lines_feature_id_key UNIQUE (feature_id);


--
-- TOC entry 6338 (class 2606 OID 17824)
-- Name: pfz_lines pfz_lines_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.pfz_lines
    ADD CONSTRAINT pfz_lines_pkey PRIMARY KEY (id);


--
-- TOC entry 6312 (class 2606 OID 16542)
-- Name: pfz_point pfz_point_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.pfz_point
    ADD CONSTRAINT pfz_point_pkey PRIMARY KEY (id);


--
-- TOC entry 6314 (class 2606 OID 16554)
-- Name: pfz_point pfz_point_sec_location_uq; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.pfz_point
    ADD CONSTRAINT pfz_point_sec_location_uq UNIQUE (sec_id, location_name);


--
-- TOC entry 6282 (class 2606 OID 16435)
-- Name: port_warnings port_warnings_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.port_warnings
    ADD CONSTRAINT port_warnings_pkey PRIMARY KEY (id);


--
-- TOC entry 6325 (class 2606 OID 17690)
-- Name: svas_advisory_status svas_advisory_status_district_id_boat_width_forecast_day_fe_key; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.svas_advisory_status
    ADD CONSTRAINT svas_advisory_status_district_id_boat_width_forecast_day_fe_key UNIQUE (district_id, boat_width, forecast_day, fetched_at);


--
-- TOC entry 6327 (class 2606 OID 17688)
-- Name: svas_advisory_status svas_advisory_status_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.svas_advisory_status
    ADD CONSTRAINT svas_advisory_status_pkey PRIMARY KEY (id);


--
-- TOC entry 6331 (class 2606 OID 17704)
-- Name: svas_advisory_text svas_advisory_text_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.svas_advisory_text
    ADD CONSTRAINT svas_advisory_text_pkey PRIMARY KEY (advisory_status_id, language_code);


--
-- TOC entry 6321 (class 2606 OID 17735)
-- Name: svas_districts svas_districts_fid_uq; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.svas_districts
    ADD CONSTRAINT svas_districts_fid_uq UNIQUE (fid_coasta);


--
-- TOC entry 6323 (class 2606 OID 17676)
-- Name: svas_districts svas_districts_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.svas_districts
    ADD CONSTRAINT svas_districts_pkey PRIMARY KEY (fid_coasta);


--
-- TOC entry 6329 (class 2606 OID 17737)
-- Name: svas_advisory_status svas_status_uq; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.svas_advisory_status
    ADD CONSTRAINT svas_status_uq UNIQUE (district_id, boat_width, forecast_day);


--
-- TOC entry 6333 (class 2606 OID 17739)
-- Name: svas_advisory_text svas_text_uq; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.svas_advisory_text
    ADD CONSTRAINT svas_text_uq UNIQUE (advisory_status_id, language_code);


--
-- TOC entry 6287 (class 2606 OID 16449)
-- Name: tide_forecasts tide_forecasts_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.tide_forecasts
    ADD CONSTRAINT tide_forecasts_pkey PRIMARY KEY (id);


--
-- TOC entry 6302 (class 2606 OID 16525)
-- Name: fishermen_warnings unique_fishermen_region; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.fishermen_warnings
    ADD CONSTRAINT unique_fishermen_region UNIQUE (region);


--
-- TOC entry 6316 (class 2606 OID 16565)
-- Name: pfz_point unique_pfz_point; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.pfz_point
    ADD CONSTRAINT unique_pfz_point UNIQUE (location_name, sec_id);


--
-- TOC entry 6284 (class 2606 OID 16527)
-- Name: port_warnings unique_port_name; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.port_warnings
    ADD CONSTRAINT unique_port_name UNIQUE (port_name);


--
-- TOC entry 6367 (class 2606 OID 18372)
-- Name: weather_daily_summary weather_daily_summary_grid_unique; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.weather_daily_summary
    ADD CONSTRAINT weather_daily_summary_grid_unique UNIQUE (grid_lat, grid_lon, forecast_date);


--
-- TOC entry 6369 (class 2606 OID 18370)
-- Name: weather_daily_summary weather_daily_summary_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.weather_daily_summary
    ADD CONSTRAINT weather_daily_summary_pkey PRIMARY KEY (id);


--
-- TOC entry 6365 (class 2606 OID 18359)
-- Name: weather_hourly weather_hourly_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.weather_hourly
    ADD CONSTRAINT weather_hourly_pkey PRIMARY KEY (id);


--
-- TOC entry 6279 (class 1259 OID 16404)
-- Name: idx_fetch_log_source_time; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_fetch_log_source_time ON public.fetch_log USING btree (source_name, fetched_at DESC);


--
-- TOC entry 6298 (class 1259 OID 17718)
-- Name: idx_fishermen_warnings_geom; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_fishermen_warnings_geom ON public.fishermen_warnings USING gist (geom);


--
-- TOC entry 6299 (class 1259 OID 16496)
-- Name: idx_fishermen_warnings_location; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_fishermen_warnings_location ON public.fishermen_warnings USING btree (latitude, longitude);


--
-- TOC entry 6300 (class 1259 OID 16514)
-- Name: idx_fishermen_warnings_time; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_fishermen_warnings_time ON public.fishermen_warnings USING btree (warning_time);


--
-- TOC entry 6375 (class 1259 OID 18600)
-- Name: idx_hazard_eez_boundary_geom; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_hazard_eez_boundary_geom ON public.hazard_eez_boundary USING gist (geom);


--
-- TOC entry 6339 (class 1259 OID 17843)
-- Name: idx_india_eez_geom; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_india_eez_geom ON public.india_eez USING gist (geom);


--
-- TOC entry 6370 (class 1259 OID 18420)
-- Name: idx_india_eez_polygon_geom; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_india_eez_polygon_geom ON public.india_eez_polygon USING gist (geom);


--
-- TOC entry 6344 (class 1259 OID 18080)
-- Name: idx_landing_centers_geom; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_landing_centers_geom ON public.landing_centers USING gist (geom);


--
-- TOC entry 6288 (class 1259 OID 16465)
-- Name: idx_lightning_location; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_lightning_location ON public.lightning_alerts USING btree (latitude, longitude);


--
-- TOC entry 6303 (class 1259 OID 17717)
-- Name: idx_location_lookup_geom; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_location_lookup_geom ON public.location_lookup USING gist (geom);


--
-- TOC entry 6354 (class 1259 OID 18116)
-- Name: idx_marine_obs_location; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_marine_obs_location ON public.marine_observations USING btree (latitude, longitude);


--
-- TOC entry 6355 (class 1259 OID 18117)
-- Name: idx_marine_obs_time; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_marine_obs_time ON public.marine_observations USING btree (observed_at);


--
-- TOC entry 6291 (class 1259 OID 16482)
-- Name: idx_ocean_reading_lookup; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_ocean_reading_lookup ON public.ocean_point_readings USING btree (variable, latitude, longitude, data_date);


--
-- TOC entry 6349 (class 1259 OID 18099)
-- Name: idx_pfz_eez_geom; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_pfz_eez_geom ON public.pfz_eez USING gist (geom);


--
-- TOC entry 6334 (class 1259 OID 17827)
-- Name: idx_pfz_lines_geom; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_pfz_lines_geom ON public.pfz_lines USING gist (geom);


--
-- TOC entry 6308 (class 1259 OID 18507)
-- Name: idx_pfz_point_geom; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_pfz_point_geom ON public.pfz_point USING gist (geom);


--
-- TOC entry 6309 (class 1259 OID 16545)
-- Name: idx_pfz_point_location; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_pfz_point_location ON public.pfz_point USING btree (latitude, longitude);


--
-- TOC entry 6310 (class 1259 OID 16546)
-- Name: idx_pfz_point_sec; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_pfz_point_sec ON public.pfz_point USING btree (sec_id, fetched_at);


--
-- TOC entry 6280 (class 1259 OID 17710)
-- Name: idx_port_warnings_geom; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_port_warnings_geom ON public.port_warnings USING gist (geom);


--
-- TOC entry 6319 (class 1259 OID 17747)
-- Name: idx_svas_districts_geom; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_svas_districts_geom ON public.svas_districts USING gist (geom);


--
-- TOC entry 6285 (class 1259 OID 16450)
-- Name: idx_tide_station_time; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_tide_station_time ON public.tide_forecasts USING btree (station_name, forecast_time);


--
-- TOC entry 6376 (class 2606 OID 17691)
-- Name: svas_advisory_status svas_advisory_status_district_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.svas_advisory_status
    ADD CONSTRAINT svas_advisory_status_district_id_fkey FOREIGN KEY (district_id) REFERENCES public.svas_districts(fid_coasta);


--
-- TOC entry 6377 (class 2606 OID 17705)
-- Name: svas_advisory_text svas_advisory_text_advisory_status_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.svas_advisory_text
    ADD CONSTRAINT svas_advisory_text_advisory_status_id_fkey FOREIGN KEY (advisory_status_id) REFERENCES public.svas_advisory_status(id);


-- Completed on 2026-11-07 13:17:09

--
-- PostgreSQL database dump complete
--

\unrestrict dbO0bYmpCmhiMT7XahLDeI1ve6MM5hntsi1Ax4aj1MD32tgSJKOP33UW8hG24bD

