package org.molgenis.data.validation;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.apache.commons.io.IOUtils;
import org.molgenis.data.AggregateResult;
import org.molgenis.data.Aggregateable;
import org.molgenis.data.AttributeMetaData;
import org.molgenis.data.CrudRepository;
import org.molgenis.data.CrudRepositoryDecorator;
import org.molgenis.data.DatabaseAction;
import org.molgenis.data.Entity;
import org.molgenis.data.MolgenisDataException;
import org.molgenis.data.Query;
import org.molgenis.fieldtypes.FieldType;
import org.molgenis.util.HugeMap;

import com.google.common.collect.Sets;

public class RepositoryValidationDecorator extends CrudRepositoryDecorator implements Aggregateable
{
	private final EntityAttributesValidator entityAttributesValidator;

	public RepositoryValidationDecorator(CrudRepository repository, EntityAttributesValidator entityAttributesValidator)
	{
		super(repository);
		this.entityAttributesValidator = entityAttributesValidator;

		if (!(repository instanceof Aggregateable))
		{
			throw new MolgenisDataException("Repository not aggregateable");
		}
	}

	@Override
	public void update(Entity entity)
	{
		validate(Arrays.asList(entity), true);
		getDecoratedRepository().update(entity);
	}

	@Override
	public void update(Iterable<? extends Entity> entities)
	{
		validate(entities, true);
		getDecoratedRepository().update(entities);
	}

	@Override
	public void delete(Entity entity)
	{
		getDecoratedRepository().delete(entity);
	}

	@Override
	public void update(List<? extends Entity> entities, DatabaseAction dbAction, String... keyName)
	{
		getDecoratedRepository().update(entities, dbAction, keyName);
	}

	@Override
	public void add(Entity entity)
	{
		validate(Arrays.asList(entity), false);
		getDecoratedRepository().add(entity);
	}

	@Override
	public Integer add(Iterable<? extends Entity> entities)
	{
		validate(entities, false);
		return getDecoratedRepository().add(entities);
	}

	@Override
	public AggregateResult aggregate(AttributeMetaData xAttr, AttributeMetaData yAttr, Query q)
	{
		return ((Aggregateable) getDecoratedRepository()).aggregate(xAttr, yAttr, q);
	}

	private void validate(Iterable<? extends Entity> entities, boolean forUpdate)
	{
		Set<ConstraintViolation> violations = Sets.newHashSet();
		for (Entity entity : entities)
		{
			violations.addAll(entityAttributesValidator.validate(entity, getEntityMetaData()));
			if (violations.size() > 4)
			{
				throw new MolgenisValidationException(violations);
			}
		}

		for (AttributeMetaData attr : getEntityMetaData().getAtomicAttributes())
		{
			if (attr.isUnique())
			{
				violations.addAll(checkUniques(entities, attr, true));
				if (violations.size() > 4)
				{
					throw new MolgenisValidationException(violations);
				}
			}
		}

		violations.addAll(checkNillable(entities));

		if (!violations.isEmpty())
		{
			throw new MolgenisValidationException(violations);
		}
	}

	protected Set<ConstraintViolation> checkNillable(Iterable<? extends Entity> entities)
	{
		Set<ConstraintViolation> violations = Sets.newHashSet();

		long rownr = 0;
		for (Entity entity : entities)
		{
			rownr++;
			for (AttributeMetaData attr : getEntityMetaData().getAtomicAttributes())
			{
				if (!attr.isNillable())
				{
					Object value = entity.get(attr.getName());
					if ((value == null) && !attr.isAuto() && (attr.getDefaultValue() == null))
					{
						String message = String.format("The attribute '%s' of entity '%s' can not be null.",
								attr.getName(), getName());
						violations.add(new ConstraintViolation(message, rownr));
						if (violations.size() > 4) return violations;
					}
				}
			}
		}

		return violations;
	}

	protected Set<ConstraintViolation> checkUniques(Iterable<? extends Entity> entities, AttributeMetaData attr,
			boolean forUpdate)
	{
		Set<ConstraintViolation> violations = Sets.newHashSet();
		HugeMap<Object, Object> values = new HugeMap<Object, Object>();// key:attribute-value value:attribute-id
		try
		{
			if (count() > 0)
			{
				// Get all attr values currently in the repository
				for (Entity entity : this)
				{
					Object value = entity.get(attr.getName());
					if (value != null)
					{
						values.put(value, entity.getIdValue());
					}
				}
			}

			// Check the new entities
			long rownr = 0;
			for (Entity entity : entities)
			{
				rownr++;
				Object value = entity.get(attr.getName());
				if (value != null)
				{
					// If adding values can never contain the value; if updating check if the id's are different, if the
					// same your updating that entity
					Object id = values.get(value);
					if (values.containsKey(value) && (!forUpdate || !entityHasId(entity, id)))
					{
						violations.add(new ConstraintViolation("Duplicate value [" + value + "] for unique attribute ["
								+ attr.getName() + "] from entity [" + getName() + "]", rownr));
						if (violations.size() > 4) break;
					}
					else
					{
						values.put(value, entity.getIdValue());
					}
				}
			}

			return violations;
		}
		finally
		{
			IOUtils.closeQuietly(values);
		}
	}

	// Check if the id of an entity equals a given id
	private boolean entityHasId(Entity entity, Object id)
	{
		FieldType idDataType = getEntityMetaData().getIdAttribute().getDataType();
		return idDataType.convert(id).equals(idDataType.convert(entity.getIdValue()));
	}

}